package com.tencent.devops.plugin.worker.task.xcode

import com.tencent.devops.common.api.util.DHUtil
import com.tencent.devops.common.pipeline.element.XcodeBuildElement2
import com.tencent.devops.plugin.worker.task.xcode.pojo.XcodeMethod
import com.tencent.devops.process.pojo.AtomErrorCode
import com.tencent.devops.process.pojo.BuildTask
import com.tencent.devops.process.pojo.BuildVariables
import com.tencent.devops.process.pojo.ErrorType
import com.tencent.devops.worker.common.logger.LoggerService
import com.tencent.devops.worker.common.task.ITask
import com.tencent.devops.worker.common.utils.ShellUtil
import com.tencent.devops.ticket.pojo.CertIOS
import com.tencent.devops.worker.common.api.ticket.CertResourceApi
import com.tencent.devops.worker.common.exception.TaskExecuteException
import com.tencent.devops.worker.common.task.TaskClassType
import java.io.File
import java.util.Base64
import java.util.regex.Pattern

@TaskClassType(classTypes = [XcodeBuildElement2.classType])
class XcodeBuildTask2 : ITask() {

    private val pairKey = DHUtil.initKey()
    private val privateKey = pairKey.privateKey
    private val publicKey = String(Base64.getEncoder().encode(pairKey.publicKey))

    // 做为全局变量方便引用
    private lateinit var buildVariables: BuildVariables
    private lateinit var workspace: File

    // 参数
    private lateinit var scheme: String
    private lateinit var configuration: String
    private lateinit var method: XcodeMethod

    // 其他属性
    private lateinit var sdk: String
    private var projectBuildStr: String = "" // -project SODA.xcodeproj 或 -workspace SODA.xcworkspace
    private var uuid: String = "" // 80c23fe1-e837-4a48-bc63-e9b2cb834de3
    private var teamId: String = "" // //CMB775PKXJ

    override fun execute(buildTask: BuildTask, buildVariables: BuildVariables, workspace: File) {
        this.buildVariables = buildVariables
        this.workspace = workspace

        val taskParams = buildTask.params ?: mapOf()
        val project = taskParams["project"] ?: throw TaskExecuteException(
            errorMsg = "project is empty",
            errorType = ErrorType.USER,
            errorCode = AtomErrorCode.USER_INPUT_INVAILD
        )
        val certId = taskParams["certId"] ?: throw TaskExecuteException(
            errorMsg = "certId is empty",
            errorType = ErrorType.USER,
            errorCode = AtomErrorCode.USER_INPUT_INVAILD
        )
        scheme = taskParams["scheme"] ?: throw TaskExecuteException(
            errorMsg = "scheme is empty",
            errorType = ErrorType.USER,
            errorCode = AtomErrorCode.USER_INPUT_INVAILD
        )
        configuration = taskParams["configuration"] ?: ""
        val ipaPath = taskParams["ipaPath"] ?: "result"
        method = XcodeMethod.parse(taskParams["method"] ?: XcodeMethod.DEVELOPMENT.type)

        switchXCode()
        init(project)
        showSdks()
        clean()
        build()
        archive()
        export(ipaPath, certId)
    }

    private fun init(project: String) {
        ShellUtil.buildEnvs = buildVariables.buildEnvs
        ShellUtil.buildId = buildVariables.buildId
        ShellUtil.dir = workspace

        // 优先选择xcworkspace目录，其次xcodeproj，不然报错
        File(project).listFiles().filter { it.isDirectory && (it.name.endsWith(".xcworkspace") or it.name.endsWith(".xcodeproj")) }.forEach {
            if (it.name.endsWith(".xcworkspace")) {
                projectBuildStr = "-workspace ${it.name}"
            } else if (projectBuildStr.isEmpty()) {
                projectBuildStr = "-project ${it.name}"
            }
        }
        if (projectBuildStr.isEmpty()) throw TaskExecuteException(
            errorMsg = "no .xcworkspace or .xcodeproj found in ($project)",
            errorType = ErrorType.USER,
            errorCode = AtomErrorCode.USER_RESOURCE_NOT_FOUND
        )

        // 列出scheme
        LoggerService.addNormalLine("show all scheme:")
        ShellUtil.execute("xcodebuild -list -project $projectBuildStr")
    }

    private fun generatePlist(certId: String): String {
        // 获取证书数据
        val certInfo = CertResourceApi().queryIos(certId, publicKey).data!!
        /*
        val certInfo = if (EnvUtils.isWorker()) {
            Client.get(BuildCertResource::class).queryIos(
                    BUILD_ID_DEFAULT,
                    VM_SEQ_ID_DEFAULT,
                    VM_NAME_DEFAULT,
                    certId,
                    publicKey)
        }
        else {
            Client.get(BuildAgentCertResource::class).queryIos(
                    AgentEnv.getProjectId(),
                    AgentEnv.getAgentId(),
                    AgentEnv.getAgentSecretKey(),
                    buildVariables.buildId,
                    certId,
                    publicKey)
        }.data!!
        */
        val proFile = getProvision(certInfo)
        val content = proFile.readText().replace("\\s", "").replace("\n", "").replace("\t", "").replace("\r", "")
        teamId = getTeamId(content)
        uuid = getUuid(content)
        val bundleId = getBundleId(content)
        val template = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\">\n" +
                "<dict>\n" +
                "       <key>teamID</key>\n" +
                "       <string>$teamId</string>\n" +
                "       <key>method</key>\n" +
                "       <string>$method</string>\n" +
                "       <dict>\n" +
                "          <key>$bundleId</key>\n" +
                "          <string>$uuid</string>\n" +
                "       </dict>\n" +
                "</dict>\n" +
                "</plist>"

        val file = File(workspace, "ipa.plist")
        file.writeBytes(template.toByteArray())
        return file.name
    }

    private fun getBundleId(content: String): String { // com.tencent.rd
        val matcher1 = Pattern.compile("<key>application-identifier</key><string>.*</string><key>com.apple.developer.team-identifier</key>", Pattern.DOTALL).matcher(content) // CMB775PKXJ.com.tencent.*
        val str1 = if (matcher1.find()) {
            val result = matcher1.group()
            result.removePrefix("<key>application-identifier</key><string>").removeSuffix("</string><key>com.apple.developer.team-identifier</key>")
        } else {
            throw TaskExecuteException(
                errorMsg = "no application-identifier found",
                errorType = ErrorType.USER,
                errorCode = AtomErrorCode.USER_RESOURCE_NOT_FOUND
            )
        }

        val matcher2 = Pattern.compile("<key>AppIDName</key><string>.*</string><key>ApplicationIdentifierPrefix</key>", Pattern.DOTALL).matcher(content)
        val str2 = if (matcher2.find()) {
            val result = matcher2.group()
            result.removePrefix("<key>AppIDName</key><string>").removeSuffix("</string><key>ApplicationIdentifierPrefix</key>")
        } else {
            throw TaskExecuteException(
                errorMsg = "no application-identifier found",
                errorType = ErrorType.USER,
                errorCode = AtomErrorCode.USER_RESOURCE_NOT_FOUND
            )
        }

        return str1.substring(str1.indexOf(".") + 1).removeSuffix("*") + str2
    }

    private fun getUuid(content: String): String {
        val matcher = Pattern.compile("<key>UUID</key><string>.*</string>").matcher(content)
        if (matcher.find()) {
            val result = matcher.group()
            return result.removePrefix("<key>UUID</key><string>").removeSuffix("</string>")
        }
        throw TaskExecuteException(
            errorMsg = "no uuid found",
            errorType = ErrorType.USER,
            errorCode = AtomErrorCode.USER_RESOURCE_NOT_FOUND
        )
    }

    private fun getTeamId(content: String): String {
        val matcher = Pattern.compile("<key>TeamIdentifier</key><array><string>.*</string></array>").matcher(content)
        if (matcher.find()) {
            val result = matcher.group()
            return result.removePrefix("<key>TeamIdentifier</key><array><string>").removeSuffix("</string></array>")
        }
        throw TaskExecuteException(
            errorMsg = "no team id found",
            errorType = ErrorType.USER,
            errorCode = AtomErrorCode.USER_RESOURCE_NOT_FOUND
        )
    }

    private fun switchXCode() {
        ShellUtil.execute(buildVariables.buildId, "sudo /usr/bin/xcode-select --switch \${XCODE_HOME}", workspace, buildVariables.buildEnvs, emptyMap(), null)
    }

    private fun showSdks() {
        val result = ShellUtil.execute("xcodebuild -showsdks")

        val matcher = Pattern.compile("iOS\\s*(\\S*)\\s*(\\S*)\\s*-sdk\\s*(iphoneos\\S*)").matcher(result)
        sdk = if (matcher.find()) matcher.group(3).removeSuffix("iOS") else ""
        LoggerService.addNormalLine("Found '$sdk' as the default sdk")
    }

    private fun clean() {
        ShellUtil.execute("xcodebuild clean $projectBuildStr -scheme $scheme -sdk $sdk")
    }

    private fun build() {
        if (configuration.isNotEmpty()) ShellUtil.execute("xcodebuild build $projectBuildStr -scheme $scheme -configuration $configuration -sdk $sdk")
        else ShellUtil.execute("xcodebuild build $projectBuildStr -scheme $scheme -sdk $sdk")
    }

    private fun archive() {
        val archivePath = "" // build/SODA.xcarchive
        val codeSignIdentify = "" // iPhone Developer: junchi he (22AYX4B947)
        if (configuration.isNotEmpty())
            ShellUtil.execute("xcodebuild archive $projectBuildStr -scheme $scheme " +
                "-configuration $configuration -archivePath \"$archivePath\" CODE_SIGN_IDENTITY=\"$codeSignIdentify\" " +
                "PROVISIONING_PROFILE=\"$uuid\" DEVELOPMENT_TEAM=$teamId -sdk $sdk")

        else ShellUtil.execute("xcodebuild archive $projectBuildStr -scheme $scheme " +
                "-archivePath \"$archivePath\" CODE_SIGN_IDENTITY=\"$codeSignIdentify\" " +
                "PROVISIONING_PROFILE=\"$uuid\" DEVELOPMENT_TEAM=$teamId -sdk $sdk")
    }

    private fun export(ipaPath: String, certId: String) {
        val archivePath = "" // build/SODA.xcarchive
        val plistFile = generatePlist(certId) // ipa.plist

        ShellUtil.execute("xcodebuild -exportArchive -archivePath $archivePath -exportPath $ipaPath -exportOptionsPlist $plistFile")
    }

    private fun getProvision(certInfo: CertIOS): File {
        val publicKeyServer = Base64.getDecoder().decode(certInfo.publicKey)
        val proContent = Base64.getDecoder().decode(certInfo.mobileProvisionContent)
        val provision = DHUtil.decrypt(proContent, publicKeyServer, privateKey)
        val provisionFile = File.createTempFile("provision_", ".mobileprovision")
        provisionFile.writeBytes(provision)

        return provisionFile
    }
}

// fun main(args: Array<String>) {
//    val content =File("D:\\docs\\2018\\cert\\2\\zy.mobileprovision").readText().replace("\\s", "").replace("\n", "").replace("\t", "")
//    println(getTeamId(content))
//    println(getUuid(content))
//    println(getBundleId(content))
// }