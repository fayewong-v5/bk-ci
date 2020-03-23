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

package com.tencent.devops.artifactory.service.bkrepo

import com.tencent.devops.artifactory.pojo.DownloadUrl
import com.tencent.devops.artifactory.pojo.FileDetail
import com.tencent.devops.artifactory.pojo.Url
import com.tencent.devops.artifactory.pojo.enums.ArtifactoryType
import com.tencent.devops.artifactory.service.PipelineService
import com.tencent.devops.artifactory.service.RepoDownloadService
import com.tencent.devops.artifactory.service.pojo.FileShareInfo
import com.tencent.devops.artifactory.util.EmailUtil
import com.tencent.devops.artifactory.util.JFrogUtil
import com.tencent.devops.artifactory.util.PathUtils
import com.tencent.devops.artifactory.util.RegionUtil
import com.tencent.devops.artifactory.util.RepoUtils
import com.tencent.devops.artifactory.util.StringUtil
import com.tencent.devops.common.api.exception.OperationException
import com.tencent.devops.common.archive.client.BkRepoClient
import com.tencent.devops.common.archive.constant.ARCHIVE_PROPS_BUILD_ID
import com.tencent.devops.common.archive.constant.ARCHIVE_PROPS_PIPELINE_ID
import com.tencent.devops.common.archive.shorturl.ShortUrlApi
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.auth.api.BSAuthProjectApi
import com.tencent.devops.common.auth.code.BSRepoAuthServiceCode
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.common.service.config.CommonConfig
import com.tencent.devops.common.service.utils.HomeHostUtil
import com.tencent.devops.notify.api.service.ServiceNotifyResource
import com.tencent.devops.process.api.service.ServiceBuildResource
import com.tencent.devops.process.api.service.ServicePipelineResource
import com.tencent.devops.project.api.service.ServiceProjectResource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.regex.Pattern
import javax.ws.rs.BadRequestException
import javax.ws.rs.NotFoundException

@Service
class BkRepoDownloadService @Autowired constructor(
    private val bkRepoService: BkRepoService,
    private val client: Client,
    private val authProjectApi: BSAuthProjectApi,
    private val artifactoryAuthServiceCode: BSRepoAuthServiceCode,
    private val pipelineService: PipelineService,
    private val bkRepoClient: BkRepoClient,
    private val shortUrlApi: ShortUrlApi,
    val commonConfig: CommonConfig
) : RepoDownloadService {
    override fun getDownloadUrl(token: String): DownloadUrl {
        // 不支持
        throw OperationException("not support")
    }

    override fun serviceGetExternalDownloadUrl(
        userId: String,
        projectId: String,
        artifactoryType: ArtifactoryType,
        path: String,
        ttl: Int,
        directed: Boolean
    ): Url {
        logger.info("serviceGetExternalDownloadUrl, userId: $userId, projectId: $projectId, " +
            "artifactoryType: $artifactoryType, path: $path, ttl: $ttl, directed: $directed")
        val normalizedPath = PathUtils.checkAndNormalizeAbsPath(path)
        val url = bkRepoService.externalDownloadUrl(
            userId,
            projectId,
            artifactoryType,
            normalizedPath,
            ttl
        )
        return Url(StringUtil.chineseUrlEncode(url))
    }

    override fun serviceGetInnerDownloadUrl(
        userId: String,
        projectId: String,
        artifactoryType: ArtifactoryType,
        argPath: String,
        ttl: Int,
        directed: Boolean
    ): Url {
        logger.info("serviceGetInnerDownloadUrl, userId: $userId, projectId: $projectId, " +
            "artifactoryType: $artifactoryType, path: $argPath, ttl: $ttl, directed: $directed")
        val normalizedPath = PathUtils.checkAndNormalizeAbsPath(argPath)
        val url = bkRepoService.internalDownloadUrl(userId, projectId, artifactoryType, normalizedPath, ttl)
        return Url(url)
    }

    override fun getDownloadUrl(
        userId: String,
        projectId: String,
        artifactoryType: ArtifactoryType,
        argPath: String
    ): Url {
        logger.info("getDownloadUrl, userId: $userId, projectId: $projectId, artifactoryType: $artifactoryType, argPath: $argPath")
        // 校验用户流水线权限？
        val normalizedPath = PathUtils.checkAndNormalizeAbsPath(argPath)
        val repo = RepoUtils.getRepoByType(artifactoryType)
        val url = "${HomeHostUtil.getHost(commonConfig.devopsIdcGateway!!)}/bkrepo/api/user/generic/$projectId/$repo$normalizedPath"
        return Url(url, url)
    }

    // 可能已废弃，待检查
    override fun getIoaUrl(
        userId: String,
        projectId: String,
        artifactoryType: ArtifactoryType,
        argPath: String
    ): Url {
        logger.info("getIoaUrl, userId: $userId, projectId: $projectId, artifactoryType: $artifactoryType, " +
            "argPath: $argPath")
        val normalizedPath = PathUtils.checkAndNormalizeAbsPath(argPath)

        // todo
        throw OperationException("not implemented")
    }

    override fun getExternalUrl(
        userId: String,
        projectId: String,
        artifactoryType: ArtifactoryType,
        path: String
    ): Url {
        logger.info("getExternalUrl, userId: $userId, projectId: $projectId, artifactoryType: $artifactoryType, " +
            "path: $path")
        val normalizedPath = PathUtils.checkAndNormalizeAbsPath(path)
        val fileInfo = bkRepoClient.getFileDetail(userId, projectId, RepoUtils.getRepoByType(artifactoryType), normalizedPath)
            ?: throw NotFoundException("文件($path)不存在")
        val properties = fileInfo.metadata
        val pipelineId = properties[ARCHIVE_PROPS_PIPELINE_ID] ?: throw RuntimeException("元数据(pipelineId)不存在")
        val buildId = properties[ARCHIVE_PROPS_BUILD_ID] ?: throw RuntimeException("元数据(buildId)不存在")
        val url = "${HomeHostUtil.outerServerHost()}/app/download/devops_app_forward.html?flag=buildArchive&projectId=$projectId&pipelineId=$pipelineId&buildId=$buildId"
        val shortUrl = shortUrlApi.getShortUrl(url, 300)
        return Url(shortUrl)
    }

    override fun shareUrl(
        userId: String,
        projectId: String,
        artifactoryType: ArtifactoryType,
        argPath: String,
        ttl: Int,
        downloadUsers: String
    ) {
        logger.info("shareUrl, userId: $userId, projectId: $projectId, artifactoryType: $artifactoryType, " +
            "argPath: $argPath, ttl: $ttl, downloadUsers: $downloadUsers")
        val path = PathUtils.checkAndNormalizeAbsPath(argPath)

        when (artifactoryType) {
            ArtifactoryType.PIPELINE -> {
                val pipelineId = pipelineService.getPipelineId(path)
                pipelineService.validatePermission(userId, projectId, pipelineId, AuthPermission.SHARE, "用户($userId)在工程($projectId)下没有流水线${pipelineId}分享权限")
            }
            ArtifactoryType.CUSTOM_DIR -> {
                if (!authProjectApi.getProjectUsers(artifactoryAuthServiceCode, projectId).contains(userId)) {
                    throw BadRequestException("用户（$userId) 没有项目（$projectId）下载权限")
                }
            }
        }
        val downloadUrl = bkRepoService.internalDownloadUrl(userId, projectId, artifactoryType, path, ttl)
        val fileDetail = bkRepoClient.getFileDetail(
            userId,
            projectId,
            RepoUtils.getRepoByType(artifactoryType),
            path
        ) ?: throw BadRequestException("文件（$path) 不存在")
        val fileName = fileDetail.nodeInfo.name
        val projectName = client.get(ServiceProjectResource::class).get(projectId).data!!.projectName

        val days = ttl / (3600 * 24)
        val title = EmailUtil.getShareEmailTitle(userId, fileName, 1)
        val body = EmailUtil.getShareEmailBody(
            projectName,
            title,
            userId,
            days,
            listOf(FileShareInfo(fileName, "", projectName, downloadUrl))
        )
        val receivers = downloadUsers.split(",").toSet()
        receivers.forEach {
            if (it.startsWith("g_")) throw BadRequestException("Invalid download users")
        }

        val emailNotifyMessage = EmailUtil.makeEmailNotifyMessage(title, body, receivers)
        client.get(ServiceNotifyResource::class).sendEmailNotify(emailNotifyMessage)
    }

    override fun getThirdPartyDownloadUrl(
        projectId: String,
        pipelineId: String,
        buildId: String,
        artifactoryType: ArtifactoryType,
        argPath: String,
        ttl: Int?,
        crossProjectId: String?,
        crossPipineId: String?,
        crossBuildNo: String?,
        region: String?,
        userId: String?
    ): List<String> {
        logger.info("getThirdPartyDownloadUrl, projectId: $projectId, pipelineId: $pipelineId, buildId: $buildId" +
            ", artifactoryType: $artifactoryType, argPath: $argPath, crossProjectId: $crossProjectId, ttl: $ttl" +
            ", crossPipineId: $crossPipineId, crossBuildNo: $crossBuildNo, region：$region, userId: $userId")
        var targetProjectId = projectId
        var targetPipelineId = pipelineId
        var targetBuildId = buildId
        if (!crossProjectId.isNullOrBlank()) {
            targetProjectId = crossProjectId!!
            targetPipelineId = crossPipineId ?: throw BadRequestException("Invalid Parameter pipelineId")
            val targetBuild = client.get(ServiceBuildResource::class).getSingleHistoryBuild(
                targetProjectId,
                targetPipelineId,
                crossBuildNo ?: throw BadRequestException("Invalid Parameter buildNo"),
                ChannelCode.BS
            ).data
            targetBuildId = (targetBuild ?: throw BadRequestException("构建不存在($crossBuildNo)")).id
        }

        var accessUserId = when {
            !userId.isNullOrBlank() -> {
                userId!!
            }
            !crossProjectId.isNullOrBlank() -> {
                client.get(ServicePipelineResource::class).getPipelineInfo(projectId, pipelineId, null).data!!.lastModifyUser
            }
            else -> {
                null
            }
        }
        logger.info("accessUserId: $accessUserId, targetProjectId: $targetProjectId, targetPipelineId: $targetPipelineId, targetBuildId: $targetBuildId")

        //校验用户权限
        if (accessUserId != null) {
            if (artifactoryType == ArtifactoryType.CUSTOM_DIR &&
                !authProjectApi.getProjectUsers(artifactoryAuthServiceCode, targetProjectId).contains(accessUserId)) {
                throw BadRequestException("用户（$accessUserId) 没有项目（$targetProjectId）下载权限)")
            }
            if (artifactoryType == ArtifactoryType.PIPELINE) {
                pipelineService.validatePermission(
                    accessUserId,
                    targetProjectId,
                    targetPipelineId,
                    AuthPermission.DOWNLOAD,
                    "用户($accessUserId)在项目($crossProjectId)下没有流水线($crossPipineId)下载构建权限"
                )
            }
        }

        val regex = Pattern.compile(",|;")
        val pathArray = regex.split(argPath)
        val fileList = mutableListOf<FileDetail>()
        pathArray.forEach { path ->
            val absPath = "/${JFrogUtil.normalize(path).removePrefix("/")}"
            val filePath = if (artifactoryType == ArtifactoryType.PIPELINE) {
                "/$targetPipelineId/$targetBuildId/${JFrogUtil.getParentFolder(absPath).removePrefix("/")}" // /$projectId/$pipelineId/$buildId/path/
            } else {
                "/${JFrogUtil.getParentFolder(absPath).removePrefix("/")}" // /path/
            }
            val fileName = JFrogUtil.getFileName(path) // *.txt

            bkRepoClient.queryByPathEqOrNameMatchOrMetadataEqAnd(
                userId = "",
                projectId = projectId,
                repoNames = listOf(RepoUtils.getRepoByType(artifactoryType)),
                filePaths = listOf(filePath),
                fileNames = listOf(fileName),
                metadata = mapOf(),
                page = 0,
                pageSize = 10000
            ).forEach {
                fileList.add(RepoUtils.toFileDetail(it))
            }
        }

        val resultList = mutableListOf<String>()
        fileList.forEach {
            val repoName = RepoUtils.getRepoByType(artifactoryType)
            val shareUri = bkRepoClient.createShareUri(
                "",
                projectId = projectId,
                repoName = repoName,
                fullPath = it.fullPath,
                downloadUsers = listOf(),
                downloadIps = listOf(),
                timeoutInSeconds = (ttl ?: 24 * 3600).toLong()
            )
            resultList.add("${RegionUtil.getRegionUrl(region)}/bkrepo/api/external/repository$shareUri")
        }
        return resultList
    }

    companion object {
        private val logger = LoggerFactory.getLogger(this::class.java)
    }
}