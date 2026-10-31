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

    public CreativeAssetController(CreativeAssetService assetService) {
        this.assetService = assetService;
    }

    public record UpdateAssetBody(String displayName, Boolean starred) {
    }

    @PostMapping("/uploads")
    public ResponseEntity<CreativeAssetService.AssetView> upload(
            CurrentActor actor,
            @RequestParam("projectId") UUID projectId,
            @RequestPart("file") MultipartFile file) {
        try (var in = file.getInputStream()) {
            var view = assetService.upload(actor, projectId,
                    file.getOriginalFilename(), in.readAllBytes());
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
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit) {
        return assetService.list(actor, projectId, mediaType, starred, origin, cursor, limit);
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
    public ResponseEntity<byte[]> content(CurrentActor actor, @PathVariable UUID assetId) {
        var content = assetService.content(actor, assetId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mimeType()))
                .header("Content-Disposition", "inline; filename*=UTF-8''"
                        + java.net.URLEncoder.encode(content.displayName(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20"))
                .body(content.bytes());
    }

    @PatchMapping("/{assetId}")
    public CreativeAssetService.AssetView update(CurrentActor actor, @PathVariable UUID assetId,
                                                 @RequestBody UpdateAssetBody body) {
        return assetService.update(actor, assetId, new CreativeAssetService.UpdateAssetRequest(
                body == null ? null : body.displayName(),
                body == null ? null : body.starred()));
    }

    @DeleteMapping("/{assetId}")
    public Map<String, Object> delete(CurrentActor actor, @PathVariable UUID assetId) {
        assetService.delete(actor, assetId);
        return Map.of("deleted", true);
    }
}
