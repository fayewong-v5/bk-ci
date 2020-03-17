/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.tencent.devops.openapi.service.op

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.client.Client
import com.tencent.devops.model.openapi.tables.records.TAppCodeGroupRecord
import com.tencent.devops.openapi.dao.AppCodeGroupDao
import com.tencent.devops.openapi.dao.AppCodeProjectDao
import com.tencent.devops.openapi.pojo.AppCodeGroup
import com.tencent.devops.openapi.pojo.AppCodeGroupResponse
import com.tencent.devops.project.api.service.ServiceProjectResource
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/**
 * @Description
 * @Date 2020/3/17
 * @Version 1.0
 */
@Service
class AppCodeService(
    private val client: Client,
    private val appCodeGroupService: AppCodeGroupService,
    private val appCodeProjectService: AppCodeProjectService
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AppCodeService::class.java)
    }

    private val appCodeProjectCache = CacheBuilder.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build<String/*appCode*/, Map<String, String>/*Map<projectId,projectId>*/>(
            object : CacheLoader<String, Map<String, String>>() {
                override fun load(appCode: String): Map<String, String> {
                    return try {
                        val projectMap = getAppCodeProject(appCode)
                        logger.info("appCode[$appCode] openapi projectMap:$projectMap.")
                        projectMap
                    } catch (t: Throwable) {
                        logger.info("appCode[$appCode] failed to get projectMap.")
                        mutableMapOf()
                    }
                }
            }
        )

    private val appCodeGroupCache = CacheBuilder.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build<String/*appCode*/, AppCodeGroup?/*AppCodeGroup*/>(
            object : CacheLoader<String, AppCodeGroup?>() {
                override fun load(appCode: String): AppCodeGroup? {
                    try {
                        val appCodeGroup = getAppCodeGroup(appCode)
                        logger.info("appCode[$appCode] openapi appCodeGroup:$appCodeGroup.")
                        return appCodeGroup
                    } catch (t: Throwable) {
                        logger.info("appCode[$appCode] failed to get appCodeGroup.")
                        return null
                    }
                }
            }
        )

    private fun getAppCodeProject(appCode: String): Map<String, String> {
        val projectList = appCodeProjectService.listProjectByAppCode(appCode)
        val result = mutableMapOf<String, String>()
        projectList.forEach {
            result[it.projectId] = it.projectId
        }
        return result
    }

    private fun getAppCodeGroup(appCode: String): AppCodeGroup? {
        val appCodeGroupResponse = appCodeGroupService.getGroup(appCode)
        return if (appCodeGroupResponse == null) {
            null
        } else {
            AppCodeGroup(
                bgId = appCodeGroupResponse.bgId,
                bgName = appCodeGroupResponse.bgName,
                deptId = appCodeGroupResponse.deptId,
                deptName = appCodeGroupResponse.bgName,
                centerId = appCodeGroupResponse.centerId,
                centerName = appCodeGroupResponse.centerName
            )
        }
    }

    fun validAppCode(appCode:String, projectId:String):Boolean {
        val appCodeProject = appCodeProjectCache.get(appCode)
        if(appCodeProject.isNotEmpty()) {
            val projectId = appCodeProject[projectId]
            if(projectId != null && projectId.isNotBlank()) {
                return true
            }
        }
        val appCodeGroup = appCodeGroupCache.get(appCode)
        if(appCodeGroup != null) {
            val projectInfo = client.get(ServiceProjectResource::class).get(projectId).data
            if(projectInfo != null) {
                if(appCodeGroup.centerId != null && projectInfo.centerId != null && appCodeGroup.centerId.toString() == projectInfo.centerId) {
                    return true
                }
                if(appCodeGroup.deptId != null && projectInfo.deptId != null && appCodeGroup.deptId.toString() == projectInfo.deptId) {
                    return true
                }
                if(appCodeGroup.bgId != null && projectInfo.bgId != null && appCodeGroup.bgId.toString() == projectInfo.bgId) {
                    return true
                }
            }
        }
        return false
    }
}