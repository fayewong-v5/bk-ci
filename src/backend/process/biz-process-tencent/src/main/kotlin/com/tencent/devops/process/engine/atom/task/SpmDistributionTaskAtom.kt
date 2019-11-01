package com.tencent.devops.process.engine.atom.task

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.io.Files
import com.google.gson.JsonParser
import com.tencent.devops.common.api.util.FileUtil
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.api.util.OkhttpUtils
import com.tencent.devops.common.pipeline.element.SpmDistributionElement
import com.tencent.devops.log.utils.LogUtils
import com.tencent.devops.process.engine.atom.AtomResponse
import com.tencent.devops.process.engine.atom.IAtomTask
import com.tencent.devops.process.pojo.AtomErrorCode
import com.tencent.devops.process.engine.common.ERROR_BUILD_TASK_CDN_FAIL
import com.tencent.devops.process.engine.exception.BuildTaskException
import com.tencent.devops.process.engine.pojo.PipelineBuildTask
import com.tencent.devops.process.pojo.ErrorType
import com.tencent.devops.process.util.CommonUtils
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.apache.commons.exec.CommandLine
import org.apache.commons.exec.DefaultExecutor
import org.apache.commons.exec.LogOutputStream
import org.apache.commons.exec.PumpStreamHandler
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
class SpmDistributionTaskAtom @Autowired constructor(
    private val rabbitTemplate: RabbitTemplate
) : IAtomTask<SpmDistributionElement> {

    @Value("\${gateway.url:#{null}}")
    private val gatewayUrl: String? = null

    @Value("\${cdntool.cmdpath}")
    private val cmdpath = "/data1/cdntool/cdntool"
    @Value("\${cdntool.master}")
    private val spmMaster = "spm-server.cdn.qq.com:8080"
    @Value("\${cdntool.querystatusurl}")
    private val querystatusurl = "http://spm.oa.com/cdntool/query_file_status.py"
    @Value("\${cdntool.rsyncip}")
    private val rsyncip = CommonUtils.getInnerIP()
    @Value("\${cdntool.rsyncport}")
    private val rsyncport = 873
    @Value("\${cdntool.rsyncmodule}")
    private val rsyncmodule = "landun_test"
    @Value("\${cdntool.rsyncuser}")
    private val rsyncuser = "root"
    @Value("\${cdntool.rsyncpwd}")
    private val rsyncpwd = "ITDev@server2"

    private var count = 0
    private val praser = JsonParser()

    private var buildId = ""
    private var projectId = ""
    private var pipelineId = ""

    override fun getParamElement(task: PipelineBuildTask): SpmDistributionElement {
        return JsonUtil.mapTo(task.taskParams, SpmDistributionElement::class.java)
    }

    override fun execute(task: PipelineBuildTask, param: SpmDistributionElement, runVariables: Map<String, String>): AtomResponse {
        logger.info("Enter SpmDistributionDelegate run...")
        val searchUrl = "http://$gatewayUrl/jfrog/api/service/search/aql"

        val cmdbAppId = param.cmdbAppId
        val cmdbAppName = parseVariable(param.cmdbAppName, runVariables)
        val rootPath = parseVariable(param.rootPath, runVariables)
        val secretKey = parseVariable(param.secretKey, runVariables)
        val regexPathsStr = parseVariable(param.regexPaths, runVariables)
        val isCustom = param.customize
        val maxRunningMins = param.maxRunningMins
        val userId = task.starter

        buildId = task.buildId
        projectId = task.projectId
        pipelineId = task.pipelineId
        val elementId = task.taskId

        val workspace = Files.createTempDir()
        var zipFile: File? = null

        try {
            regexPathsStr.split(",").forEach { regex ->
                val requestBody = getRequestBody(regex, isCustom)
                LogUtils.addLine(rabbitTemplate, buildId, "requestBody:$requestBody", elementId, task.containerHashId, task.executeCount ?: 1)
                val request = Request.Builder()
                        .url(searchUrl)
                        .post(RequestBody.create(MediaType.parse("text/plain; charset=utf-8"), requestBody))
                        .build()

                OkhttpUtils.doHttp(request).use { response ->
                    val body = response.body()!!.string()

                    val results = praser.parse(body).asJsonObject["results"].asJsonArray
                    for (i in 0 until results.size()) {
                        count++
                        val obj = results[i].asJsonObject
                        val path = getPath(obj["path"].asString, obj["name"].asString, isCustom)
                        val url = getUrl(path, isCustom)
                        // val filePath = getFilePath(obj["path"].asString, obj["name"].asString, isCustom)
                        val destFile = File(workspace, obj["name"].asString)
                        OkhttpUtils.downloadFile(url, destFile)
                        logger.info("save file : ${destFile.canonicalPath} (${destFile.length()})")
                    }
                }
            }
            logger.info("$count file(s) will be distribute...")
            LogUtils.addLine(rabbitTemplate, buildId, "$count file(s) will be distribute...", elementId, task.containerHashId, task.executeCount ?: 1)
            if (count == 0) throw RuntimeException("No file to distribute")
            zipFile = FileUtil.zipToCurrentPath(workspace)
            logger.info("Zip file: ${zipFile.canonicalPath}")
            LogUtils.addLine(rabbitTemplate, buildId, "Zip file: $zipFile", elementId, task.containerHashId, task.executeCount ?: 1)

            // 创建cdntool的配置文件
            val cdnToolConfFile = File(workspace, "cdntool.conf")
            cdnToolConfFile.writeText("bu_id = $cmdbAppId \n")
            cdnToolConfFile.appendText("bu_name = $cmdbAppName \n")
            cdnToolConfFile.appendText("master = $spmMaster \n")
            cdnToolConfFile.appendText("secret_key = $secretKey \n")
            cdnToolConfFile.appendText("bu_rtx = $userId \n")
            cdnToolConfFile.appendText("rsync_dir = /tmp/distribute/ \n")
            cdnToolConfFile.appendText("rsync_ip = $rsyncip \n")
            cdnToolConfFile.appendText("rsync_port = $rsyncport \n")
            cdnToolConfFile.appendText("rsync_module = $rsyncmodule \n")
            cdnToolConfFile.appendText("rsync_user = $rsyncuser \n")
            cdnToolConfFile.appendText("rsync_pwd = $rsyncpwd \n")

            // 执行cdntool命令
            val distributeFile = zipFile.canonicalPath.toString().substring("/tmp/distribute/".length)
            val cmd = "$cmdpath -c $workspace/cdntool.conf -f $distributeFile -o zip"
            logger.info("cdntool cmd: $cmd")
            val responseListStr = executeShell(cmd, File("/tmp/distribute/"))
            logger.info("spm cdntool return: $responseListStr")
            val response: Map<String, Any> = jacksonObjectMapper().readValue(responseListStr)
            if (response["ret"] != 0) {
                val msg = response["error"]
                logger.error("Spm return not 0,distribute to cdn failed. msg: $msg")
                LogUtils.addRedLine(rabbitTemplate, buildId, "分发CDN失败. msg: $msg", elementId, task.containerHashId, task.executeCount ?: 1)
                return AtomResponse(
                    buildStatus = BuildStatus.FAILED,
                    errorType = ErrorType.USER,
                    errorCode = AtomErrorCode.USER_TASK_OPERATE_FAIL,
                    errorMsg = "分发CDN失败. msg: $msg"
                )
            }

            logger.info("Distribute to cdn request success, now get the process...")
            if (waitForDistribute(rootPath, distributeFile, cmdbAppId, maxRunningMins)) {
                logger.info("CDN distribute success.")
                LogUtils.addLine(rabbitTemplate, buildId, "CDN distribute success.", elementId, task.containerHashId, task.executeCount ?: 1)
            }

            LogUtils.addLine(rabbitTemplate, buildId, "Distribute to CDN done", elementId, task.containerHashId, task.executeCount ?: 1)
        } finally {
            workspace.deleteRecursively()
            zipFile?.delete()
        }
        return AtomResponse(BuildStatus.SUCCEED)
    }

    fun executeShell(command: String, workspace: File): String {
        val result = StringBuilder()
        val cmdLine = CommandLine.parse(command)
        val executor = DefaultExecutor()
        executor.workingDirectory = workspace
        val outputStream = object : LogOutputStream() {
            override fun processLine(line: String?, level: Int) {
                if (line == null)
                    return
                result.append(line)
            }
        }

        val errorStream = object : LogOutputStream() {
            override fun processLine(line: String?, level: Int) {
                if (line == null) {
                    return
                }
                result.append(line)
            }
        }
        executor.streamHandler = PumpStreamHandler(outputStream, errorStream)
        try {
            val exitCode = executor.execute(cmdLine)
            if (exitCode != 0) {
                throw RuntimeException("Script command execution failed with exit code($exitCode)")
            }
        } catch (t: Throwable) {
            logger.warn("Fail to execute the command($command)", t)
            throw t
        }
        return result.toString()
    }

    private fun waitForDistribute(rootPath: String, distributePath: String, cmdbAppId: Int, timeout: Int): Boolean {
        logger.info("waiting for cdn done, timeout setting: ${timeout}s")
        val startTime = System.currentTimeMillis()
        while (!queryFileStatus(rootPath, distributePath, cmdbAppId)) {
            if (System.currentTimeMillis() - startTime > timeout * 1000) {
                logger.error("cdn distribute timeout")
                return false
            }
            Thread.sleep(3 * 1000)
        }
        return true
    }

    private fun queryFileStatus(rootPath: String, distributePath: String, cmdbAppId: Int): Boolean {
        var rootPathValue = rootPath
        if (!rootPathValue.startsWith("/")) {
            rootPathValue = "/$rootPath"
        }
        if (!rootPathValue.endsWith("/")) {
            rootPathValue = "$rootPathValue/"
        }
        val url = "$querystatusurl?compatible=on&buid=$cmdbAppId&filename=$rootPathValue$distributePath"
        logger.info("Get url: $url")
        val request = Request.Builder()
                .url(url)
                .get()
                .build()

        OkhttpUtils.doHttp(request).use { response ->
            val body = response.body()!!.string()
            logger.info("Response body: $body")

            val responseJson = praser.parse(body).asJsonObject
            val retCode = responseJson["code"].asInt
            if (0 != retCode) {
                logger.error("Response failed. msg: ${responseJson["msg"].asString}")
                throw BuildTaskException(ERROR_BUILD_TASK_CDN_FAIL, "分发CDN失败")
            }

            val results = praser.parse(body).asJsonObject["file_list"].asJsonArray
            for (i in 0 until results.size()) {
                val obj = results[i].asJsonObject
                val finishRate = obj["finish_rate"].asString
                if ("100%" == finishRate) {
                    return true
                }
            }
        }

        return false
    }

    private fun getRequestBody(regex: String, isCustom: Boolean): String {
        val pathPair = getPathPair(regex)
        return if (isCustom) {
            "items.find(\n" +
                    "    {\n" +
                    "        \"repo\":{\"\$eq\":\"generic-local\"}, \"path\":{\"\$eq\":\"bk-custom/$projectId${pathPair.first}\"}, \"name\":{\"\$match\":\"${pathPair.second}\"}\n" +
                    "    }\n" +
                    ")"
        } else {
            "items.find(\n" +
                    "    {\n" +
                    "        \"repo\":{\"\$eq\":\"generic-local\"}, \"path\":{\"\$eq\":\"bk-archive/$projectId/$pipelineId/$buildId${pathPair.first}\"}, \"name\":{\"\$match\":\"${pathPair.second}\"}\n" +
                    "    }\n" +
                    ")"
        }
    }

    // aa/test/*.txt
    // first = /aa/test
    // second = *.txt
    private fun getPathPair(regex: String): Pair<String, String> {
        if (regex.endsWith("/")) return Pair("/" + regex.removeSuffix("/"), "*")
        val index = regex.lastIndexOf("/")

        if (index == -1) return Pair("", regex) // a.txt

        return Pair("/" + regex.substring(0, index), regex.substring(index + 1))
    }

    // 处理jfrog传回的路径
    private fun getPath(path: String, name: String, isCustom: Boolean): String {
        return if (isCustom) {
            path.substring(path.indexOf("/") + 1).removePrefix("/$projectId") + "/" + name
        } else {
            path.substring(path.indexOf("/") + 1).removePrefix("/$projectId/$pipelineId/$buildId") + "/" + name
        }
    }

    // 生成保存本地的路径
    private fun getFilePath(path: String, name: String, isCustom: Boolean): String {
        return if (isCustom) {
            path.substring(path.indexOf("/")).removePrefix("/$projectId") + "/" + name
        } else {
            path.substring(path.indexOf("/")).removePrefix("/$projectId/$pipelineId/$buildId") + "/" + name
        }
    }

    // 获取jfrog传回的url
    private fun getUrl(realPath: String, isCustom: Boolean): String {
        return if (isCustom) {
            "http://$gatewayUrl/jfrog/storage/service/custom/$realPath"
        } else {
            "http://$gatewayUrl/jfrog/storage/service/archive/$realPath"
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SpmDistributionTaskAtom::class.java)
    }
}
