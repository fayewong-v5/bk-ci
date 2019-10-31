package com.tencent.devops.notify.resources.blueking

import com.tencent.devops.common.api.exception.ParamBlankException
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.web.RestResource
import com.tencent.devops.notify.api.blueking.ServiceNotifyResource
import com.tencent.devops.notify.blueking.model.EmailNotifyMessage
import com.tencent.devops.notify.blueking.model.RtxNotifyMessage
import com.tencent.devops.notify.blueking.model.SmsNotifyMessage
import com.tencent.devops.notify.blueking.model.WechatNotifyMessage
import com.tencent.devops.notify.blueking.service.EmailService
import com.tencent.devops.notify.blueking.service.RtxService
import com.tencent.devops.notify.blueking.service.SmsService
import com.tencent.devops.notify.blueking.service.WechatService
import org.springframework.beans.factory.annotation.Autowired

@RestResource
class ServiceNotifyResourceImpl @Autowired constructor(
    private val emailService: EmailService,
    private val rtxService: RtxService,
    private val smsService: SmsService,
    private val wechatService: WechatService
) : ServiceNotifyResource {

    override fun sendRtxNotify(message: RtxNotifyMessage): Result<Boolean> {
        if (message.title.isNullOrEmpty()) {
            throw ParamBlankException("无效的标题")
        }
        if (message.body.isNullOrEmpty()) {
            throw ParamBlankException("无效的内容")
        }
        if (message.isReceiversEmpty()) {
            throw ParamBlankException("无效的接收者")
        }
        rtxService.sendMqMsg(message)
        return Result(true)
    }

    override fun sendEmailNotify(message: EmailNotifyMessage): Result<Boolean> {
        if (message.title.isNullOrEmpty()) {
            throw ParamBlankException("无效的标题")
        }
        if (message.body.isNullOrEmpty()) {
            throw ParamBlankException("无效的内容")
        }
        if (message.isReceiversEmpty()) {
            throw ParamBlankException("无效的接收者")
        }
        emailService.sendMqMsg(message)
        return Result(true)
    }

    override fun sendWechatNotify(message: WechatNotifyMessage): Result<Boolean> {
        if (message.body.isNullOrEmpty()) {
            throw ParamBlankException("无效的内容")
        }
        if (message.isReceiversEmpty()) {
            throw ParamBlankException("无效的接收者")
        }
        wechatService.sendMqMsg(message)
        return Result(true)
    }

    override fun sendSmsNotify(message: SmsNotifyMessage): Result<Boolean> {
        if (message.body.isNullOrEmpty()) {
            throw ParamBlankException("无效的内容")
        }
        if (message.isReceiversEmpty()) {
            throw ParamBlankException("无效的接收者")
        }
        smsService.sendMqMsg(message)
        return Result(true)
    }
}