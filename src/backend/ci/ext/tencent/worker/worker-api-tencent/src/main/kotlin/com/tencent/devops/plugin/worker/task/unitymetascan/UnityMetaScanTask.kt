package com.tencent.devops.plugin.worker.task.unitymetascan

import com.tencent.devops.common.log.Ansi.Companion.ansi
import com.tencent.devops.process.pojo.AtomErrorCode
import com.tencent.devops.process.pojo.BuildTask
import com.tencent.devops.process.pojo.BuildVariables
import com.tencent.devops.process.pojo.ErrorType
import com.tencent.devops.worker.common.exception.TaskExecuteException
import com.tencent.devops.worker.common.logger.LoggerService
import com.tencent.devops.worker.common.task.ITask
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

class UnityMetaScanTask : ITask() {

    companion object {
        private val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    }

    override fun execute(buildTask: BuildTask, buildVariables: BuildVariables, workspace: File) {
        val taskParams = buildTask.params ?: mapOf()
        val isScanException = taskParams["isScanException"]?.toBoolean() ?: false

        // 找assets目录
        var assetsPath = ""
        workspace.walk().forEach {
            if (it.isDirectory && it.name == "Assets") {
                assetsPath = it.absolutePath
                return
            }
        }
        if (assetsPath.isEmpty()) {
            LoggerService.addNormalLine("Assets dir has not be found in ${ansi().fgRed().a(workspace.absolutePath).reset()}")
            if (isScanException) throw TaskExecuteException(
                errorMsg = "Assets dir has not be found in $workspace",
                errorType = ErrorType.USER,
                errorCode = AtomErrorCode.USER_RESOURCE_NOT_FOUND
            )
            return
        }

        // 开始扫描
        LoggerService.addNormalLine("${ansi().bold().a(assetsPath).reset()}  will be scan")
        val r1 = findMetaFile(assetsPath)
        val r2 = compareGuid(assetsPath)
        if (!r1.get() || !r2.get()) {
            LoggerService.addNormalLine("${ansi().fgRed().a("scan meta files fail:$assetsPath").reset()}")
            if (isScanException) throw TaskExecuteException(
                errorMsg = "Scan meta files fail!",
                errorType = ErrorType.USER,
                errorCode = AtomErrorCode.USER_TASK_OPERATE_FAIL
            )
            return
        }
        LoggerService.addNormalLine("${ansi().fgRed().a("scan meta files success:$assetsPath").reset()}")
    }

    private fun findMetaFile(path: String): Future<Boolean> {
        return executor.submit(Callable<Boolean> {
            var result = true
            var counterD = 0
            var counterF = 0
            File(path).walk().filter { return@filter it.isDirectory }.forEach { file ->
                // 只有文件夹才需要遍历meta文件，只扫一层即可
                val childFileNames = file.list()
                file.walk().maxDepth(1).forEach inside@{
                    if (it.name.startsWith(".") || it.name.endsWith(".meta") || it.name == file.name) return@inside
                    val metaFileName = it.name + ".meta"
                    if (!childFileNames.contains(metaFileName)) {
                        if (it.isDirectory && it.list().isNotEmpty()) {
                            counterD++
                            LoggerService.addNormalLine("${ansi().fgRed().a(it.absolutePath).reset()} dir have not find meta file")
                        }
                        if (it.isFile) {
                            counterF++
                            LoggerService.addNormalLine("${ansi().fgRed().a(it.absolutePath).reset()} file have not find meta file")
                        }
                        result = false
                    }
                }
            }
            LoggerService.addNormalLine(ansi().fgYellow().a("Dir Not Found Meta file total: counterD").reset().toString())
            LoggerService.addNormalLine(ansi().fgYellow().a("File Not Found Meta file total: counterF").reset().toString())
            return@Callable result
        })
    }

    private fun compareGuid(dir: String): Future<Boolean> {
        return executor.submit(Callable<Boolean> {
            var result = true
            val guidDict = mutableMapOf<String, String>()
            val guidSet = mutableSetOf<String>()

            File(dir).walk().filter({
                return@filter it.isFile && it.name.endsWith(".meta")
            }).forEach { file ->
                val path = file.absolutePath
                if (file.length() == 0L) {
                    LoggerService.addNormalLine("${ansi().fgRed().a(path).reset()} file size is zero")
                    return@forEach
                }

                // 提取guid
                var guid = ""
                file.readLines().forEach Inside@{
                    if (it.contains("guid:")) {
                        guid = it.trim('\n').substring(5)
                        return@Inside
                    }
                }
                guidDict.forEach inside@{ key, value ->
                    if (value == guid) {
                        if (path == key) return@inside
                        LoggerService.addNormalLine("${ansi().fgRed().a(value).reset()} Guid is not unique,file name: ${ansi().fgYellow().a(key).reset()}  ; ${ansi().fgYellow().a(path).reset()} ")
                        guidSet.add(key)
                        guidSet.add(path)
                        result = false
                    }
                }
                guidDict.put(path, guid)
            }

            LoggerService.addNormalLine(ansi().fgYellow().a("Guid Not Unique file total: ${guidSet.size}").reset().toString())
            return@Callable result
        })
    }
}