@file:Suppress("UNCHECKED_CAST")

package com.tencent.devops.process.engine.atom.task

import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.log.utils.LogUtils
import com.tencent.devops.common.pipeline.element.ItestReviewCreateElement
import com.tencent.devops.process.engine.atom.AtomResponse
import com.tencent.devops.process.engine.atom.IAtomTask
import com.tencent.devops.process.engine.pojo.PipelineBuildTask
import com.tencent.devops.common.itest.api.ITestClient
import com.tencent.devops.common.itest.api.request.ProcessTestMasterRequest
import com.tencent.devops.common.itest.api.request.ReviewCreateRequest
import com.tencent.devops.process.util.CommonUtils
import com.tencent.devops.ticket.pojo.enums.CredentialType
import net.sf.json.JSONObject
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Component
@Scope(SCOPE_PROTOTYPE)
class ItestReviewCreateTaskAtom @Autowired constructor(
    private val client: Client,
    private val rabbitTemplate: RabbitTemplate
) : IAtomTask<ItestReviewCreateElement> {

    override fun getParamElement(task: PipelineBuildTask): ItestReviewCreateElement {
        return JsonUtil.mapTo(task.taskParams, ItestReviewCreateElement::class.java)
    }

    override fun execute(task: PipelineBuildTask, param: ItestReviewCreateElement, runVariables: Map<String, String>): AtomResponse {
        val buildId = task.buildId
        val taskId = task.taskId

//        if (param.itestProjectId.isBlank()) {
//            logger.error("itestProjectId is not init of build($buildId)")
//            LogUtils.addRedLine(client, buildId, "itestProjectId is not init", taskId)
//            return BuildStatus.FAILED
//        }
//
//        if (param.ticketId.isBlank()) {
//            logger.warn("ticketId is not init of build($buildId)")
//            LogUtils.addRedLine(client, buildId, "ticketId is not init", taskId)
//            return BuildStatus.FAILED
//        }
//
//        if (param.versionType.isBlank()) {
//            logger.warn("versionType is not init of build($buildId)")
//            LogUtils.addRedLine(client, buildId, "versionType is not init", taskId)
//            return BuildStatus.FAILED
//        }
//
//        if (param.versionName.isBlank()) {
//            logger.warn("versionName is not init of build($buildId)")
//            LogUtils.addRedLine(client, buildId, "versionName is not init", taskId)
//            return BuildStatus.FAILED
//        }
//
//        if (param.baselineName.isBlank()) {
//            logger.warn("baselineName is not init of build($buildId)")
//            LogUtils.addRedLine(client, buildId, "baselineName is not init", taskId)
//            return BuildStatus.FAILED
//        }
//
//        if (param.releaseTime == null) {
//            logger.warn("releaseTime is not init of build($buildId)")
//            LogUtils.addRedLine(client, buildId, "releaseTime is not init", taskId)
//            return BuildStatus.FAILED
//        }
//
//        if (param.description.isBlank()) {
//            logger.warn("description is not init of build($buildId)")
//            LogUtils.addRedLine(client, buildId, "description is not init", taskId)
//            return BuildStatus.FAILED
//        }
//
//        if (param.targetPath.isBlank()) {
//            logger.warn("targetPath is not init of build($buildId)")
//            LogUtils.addRedLine(client, buildId, "targetPath is not init", taskId)
//            return BuildStatus.FAILED
//        }
        val userId = task.starter
        val itestProjectIdValue = parseVariable(param.itestProjectId, runVariables)
        val ticketIdValue = parseVariable(param.ticketId, runVariables)
        val versionTypeValue = parseVariable(param.versionType, runVariables)
        val versionNameValue = parseVariable(param.versionName, runVariables)
        val baselineNameValue = parseVariable(param.baselineName, runVariables)
        val releaseTimeValue = param.releaseTime
        val descriptionValue = parseVariable(param.description, runVariables)
//        val isCustom = parseVariable(param.customize, runVariables).toBoolean()
//        val targetPathValue = parseVariable(param.targetPath, runVariables)

        // 从ticket服务拿到itest api的用户名和token
        val projectId = task.projectId
        val ticketsMap = CommonUtils.getCredential(client, projectId, ticketIdValue, CredentialType.USERNAME_PASSWORD)
        val apiUser = ticketsMap["v1"].toString()
        val token = ticketsMap["v2"].toString()

        val itestClient = ITestClient(apiUser, token)
        // 从itest获取测试经理
        val processTestMasterRequest = ProcessTestMasterRequest(itestProjectIdValue)
        val processTestMasterResponse = itestClient.getProcessTestMaster(processTestMasterRequest)
        val testMaster = processTestMasterResponse.data["test_master"]

        // 创建审核单
        val request = ReviewCreateRequest(itestProjectIdValue, userId, releaseTimeValue.toString(),
                descriptionValue, versionNameValue, baselineNameValue, versionTypeValue, testMaster, "")
        val response = itestClient.createReview(request)

        logger.info("Create process for itest success!")
        val processJson = JSONObject.fromObject(response.data).toString()
        LogUtils.addLine(rabbitTemplate, buildId, "创建itest审核单成功, 详情：$processJson", taskId, task.containerHashId, task.executeCount ?: 1)
        return AtomResponse(BuildStatus.SUCCEED)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ItestReviewCreateTaskAtom::class.java)
    }
}
