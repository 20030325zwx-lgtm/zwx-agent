package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.creative.asset.CreativeAssetService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

/**
 * 素材 API（C06）：上传（multipart）、列表/筛选、内容读取（授权流式）、
 * 收藏/改名、软删除（活动任务引用时 409）。
 */
@RestController
@RequestMapping("/ai/creative/assets")
public class CreativeAssetController {

    private final CreativeAssetService assetService;
    private final com.zwx.zwxagent.platform.security.PlaybackSignatureService playbackSignatures;

    public CreativeAssetController(CreativeAssetService assetService,
                                   com.zwx.zwxagent.platform.security.PlaybackSignatureService playbackSignatures) {
        this.assetService = assetService;
        this.playbackSignatures = playbackSignatures;
    }

    public record UpdateAssetBody(String displayName, Boolean starred, String businessType) {
    }

    @PostMapping("/uploads")
    public ResponseEntity<CreativeAssetService.AssetView> upload(
            CurrentActor actor,
            @RequestParam("projectId") UUID projectId,
            @RequestPart("file") MultipartFile file) {
        try (var in = file.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            // 魔数分流（V1-A）：视频（MP4/MOV/WebM）与图片共用同一上传入口
            boolean video = com.zwx.zwxagent.creative.video.VideoProbe.isMp4(bytes)
                    || (bytes.length >= 4 && (bytes[0] & 0xff) == 0x1A && (bytes[1] & 0xff) == 0x45
                        && (bytes[2] & 0xff) == 0xDF && (bytes[3] & 0xff) == 0xA3);
            var view = video
                    ? assetService.uploadVideo(actor, projectId, file.getOriginalFilename(), bytes)
                    : assetService.upload(actor, projectId, file.getOriginalFilename(), bytes);
            return ResponseEntity.status(201).body(view);
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("读取上传内容失败", exception);
        }
    }

    @GetMapping
    public CreativeAssetService.AssetList list(CurrentActor actor,
                                               @RequestParam(required = false) UUID projectId,
                                               @RequestParam(required = false) String mediaType,
                                               @RequestParam(required = false) String origin,
                                               @RequestParam(defaultValue = "false") boolean starred,
                                               @RequestParam(required = false) String businessType,
                                               @RequestParam(defaultValue = "false") boolean trashed,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit) {
        return assetService.list(actor, projectId, mediaType, starred, origin, businessType, trashed, cursor, limit);
    }

    @GetMapping("/{assetId}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(CurrentActor actor, @PathVariable UUID assetId) {
        byte[] bytes = assetService.thumbnail(actor, assetId);
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header("Cache-Control", "private, max-age=3600")
                .body(bytes);
    }

    @GetMapping("/{assetId}/content")
    public ResponseEntity<byte[]> content(CurrentActor actor, @PathVariable UUID assetId,
                                          jakarta.servlet.http.HttpServletRequest request,
                                          jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        var content = assetService.content(actor, assetId);
        // 视频走流式 + Range（拖动/续播必须 206）；图片保持 byte[] 兼容
        String range = request.getHeader("Range");
        if (range != null && !range.isBlank() && content.mimeType() != null
                && content.mimeType().startsWith("video/")) {
            videoRange(actor, assetId, content.displayName(), range, response);
            return null; // 响应已直接写出
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mimeType()))
                .header("Content-Disposition", "inline; filename*=UTF-8''"
                        + java.net.URLEncoder.encode(content.displayName(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20"))
                .body(content.bytes());
    }

    /** 视频分块响应（V1-A）：bytes=start-end / bytes=start- / bytes=-suffix；非法区间 416。直接写响应避免转换器歧义。 */
    private void videoRange(CurrentActor actor, UUID assetId, String displayName, String range,
                            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        var meta = assetService.playbackMeta(actor, assetId);
        long size = meta.size();
        long start = 0;
        long end = size - 1;
        boolean partial = false;
        boolean invalid = false;
        String normalized = range.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("bytes=")) {
            String spec = normalized.substring(6);
            int dash = spec.indexOf('-');
            String startText = dash > 0 ? spec.substring(0, dash) : "";
            String endText = dash >= 0 ? spec.substring(dash + 1) : "";
            try {
                if (startText.isBlank() && !endText.isBlank()) {
                    long suffix = Long.parseLong(endText);
                    if (suffix <= 0) {
                        invalid = true;
                    } else {
                        start = Math.max(0, size - suffix);
                        end = size - 1;
                        partial = true;
                    }
                } else if (!startText.isBlank()) {
                    start = Long.parseLong(startText);
                    end = endText.isBlank() ? size - 1 : Long.parseLong(endText);
                    if (end > size - 1) end = size - 1;
                    partial = true;
                }
            } catch (NumberFormatException exception) {
                invalid = true;
            }
        } else if (!normalized.isBlank()) {
            invalid = true;
        }
        if (invalid || size <= 0 || start < 0 || start > end || start >= size) {
            response.setStatus(416);
            response.setHeader("Content-Range", "bytes */" + Math.max(0, size));
            return;
        }
        long length = end - start + 1;
        response.setStatus(partial ? 206 : 200);
        response.setContentType(meta.mimeType());
        response.setHeader("Accept-Ranges", "bytes");
        response.setContentLengthLong(length);
        response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        response.setHeader("Content-Disposition", "inline; filename*=UTF-8''"
                + java.net.URLEncoder.encode(displayName, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20"));
        try (var in = assetService.openStream(actor, assetId)) {
            long skipped = in.skip(start);
            while (skipped < start) {
                long step = in.skip(start - skipped);
                if (step <= 0) return;
                skipped += step;
            }
            byte[] buffer = new byte[16 * 1024];
            long remaining = length;
            var out = response.getOutputStream();
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    /** 视频播放签名 URL（V1-A）：&lt;video&gt; 无法带 Authorization，前端换取短时效免头播放地址。
     *  返回控制器相对路径（不含 /api context-path），由前端拼接 API_BASE_URL（开发环境跨端口、生产同源）。 */
    @GetMapping("/{assetId}/playback-url")
    public Map<String, Object> playbackUrl(CurrentActor actor, @PathVariable UUID assetId) {
        var token = playbackSignatures.issue(actor, assetId);
        return Map.of("url", "/ai/creative/assets/" + assetId + "/content?playback="
                        + java.net.URLEncoder.encode(token.value(), java.nio.charset.StandardCharsets.UTF_8),
                "expiresAt", token.expiresAt().toString());
    }

    @PatchMapping("/{assetId}")
    public CreativeAssetService.AssetView update(CurrentActor actor, @PathVariable UUID assetId,
                                                 @RequestBody UpdateAssetBody body) {
        return assetService.update(actor, assetId, new CreativeAssetService.UpdateAssetRequest(
                body == null ? null : body.displayName(),
                body == null ? null : body.starred(),
                body == null ? null : body.businessType()));
    }

    /** 回收站恢复：素材从 DELETED 回到 READY，不重新生成也不移动对象。 */
    @PostMapping("/{assetId}/restore")
    public CreativeAssetService.AssetView restore(CurrentActor actor, @PathVariable UUID assetId) {
        return assetService.restore(actor, assetId);
    }

    /**
     * 素材使用位置反查（跨项目）：镜头候选 + 角色/地点参考图。
     * 素材库徽标与详情用它回答"这张图现在被谁用着"，删除前也能看到引用。
     */
    @GetMapping("/{assetId}/usage")
    public CreativeAssetService.AssetUsage usage(CurrentActor actor, @PathVariable UUID assetId) {
        return assetService.usage(actor, assetId);
    }

    @DeleteMapping("/{assetId}")
    public Map<String, Object> delete(CurrentActor actor, @PathVariable UUID assetId) {
        assetService.delete(actor, assetId);
        return Map.of("deleted", true);
    }
}
