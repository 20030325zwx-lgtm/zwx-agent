package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.platform.settings.ConfigurationScope;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import com.zwx.zwxagent.platform.settings.ModelCatalogService.BindingRequest;
import com.zwx.zwxagent.platform.settings.ModelCatalogService.CatalogView;
import com.zwx.zwxagent.platform.settings.ModelCatalogService.ModelEntryRequest;
import com.zwx.zwxagent.platform.settings.ModelCatalogService.ProviderRequest;
import com.zwx.zwxagent.platform.settings.ModelCatalogService.ProviderView;
import com.zwx.zwxagent.platform.settings.ModelCatalogService.TestRequest;
import com.zwx.zwxagent.platform.settings.ModelTestResult;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模型供应商目录 API：/settings/providers、/settings/bindings。
 * 旧 /settings/models 端点保留兼容；系统级操作要求管理员。
 */
@RestController
@RequestMapping("/settings")
public class ModelCatalogController {

    private final ModelCatalogService catalogService;

    public ModelCatalogController(ModelCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/catalog")
    public CatalogView catalog(CurrentActor actor) {
        return catalogService.catalog(actor.userId());
    }

    @PostMapping("/providers")
    public ProviderView createProvider(CurrentActor actor, @RequestBody ProviderRequest request) {
        // 个人供应商归属当前用户；系统级供应商由个人/系统作用域绑定区分，不在创建时区分
        return catalogService.createProvider(actor.userId(), request);
    }

    @PutMapping("/providers/{providerId}")
    public ProviderView updateProvider(CurrentActor actor, @PathVariable long providerId,
                                       @RequestBody ProviderRequest request) {
        return catalogService.updateProvider(actor.userId(), providerId, request);
    }

    @DeleteMapping("/providers/{providerId}")
    public Map<String, Object> deleteProvider(CurrentActor actor, @PathVariable long providerId) {
        catalogService.deleteProvider(actor.userId(), providerId);
        return Map.of("deleted", true);
    }

    @PostMapping("/providers/{providerId}/models")
    public ProviderView addModel(CurrentActor actor, @PathVariable long providerId,
                                 @RequestBody ModelEntryRequest request) {
        return catalogService.addModelEntry(actor.userId(), providerId, request);
    }

    @PutMapping("/providers/{providerId}/models/{modelRowId}")
    public ProviderView updateModel(CurrentActor actor, @PathVariable long providerId,
                                    @PathVariable long modelRowId, @RequestBody ModelEntryRequest request) {
        return catalogService.updateModelEntry(actor.userId(), providerId, modelRowId, request);
    }

    @DeleteMapping("/providers/{providerId}/models/{modelRowId}")
    public Map<String, Object> deleteModel(CurrentActor actor, @PathVariable long providerId,
                                           @PathVariable long modelRowId) {
        catalogService.deleteModelEntry(actor.userId(), providerId, modelRowId);
        return Map.of("deleted", true);
    }

    @PostMapping("/providers/{providerId}/test")
    public ModelTestResult testProvider(CurrentActor actor, @PathVariable long providerId,
                                        @RequestBody(required = false) TestRequest draft) {
        return catalogService.testProvider(actor.userId(), providerId, draft);
    }

    /** 从中转站（OpenAI 兼容）拉取 /v1/models 并增量入库，新模型默认停用。 */
    @PostMapping("/providers/{providerId}/fetch-models")
    public ModelCatalogService.FetchModelsResult fetchModels(CurrentActor actor, @PathVariable long providerId) {
        return catalogService.fetchModels(actor.userId(), providerId);
    }

    @PutMapping("/bindings/{purpose}")
    public CatalogView setBinding(CurrentActor actor, @PathVariable String purpose,
                                  @RequestBody BindingRequest request) {
        if (isSystemScope(request)) actor.requireAdmin();
        return catalogService.setBinding(actor.userId(), scopeOf(request), purpose, request);
    }

    @DeleteMapping("/bindings/{purpose}")
    public CatalogView clearBinding(CurrentActor actor, @PathVariable String purpose,
                                    @RequestParam(defaultValue = "USER") String scope) {
        ConfigurationScope scopeType = parseScope(scope);
        if (scopeType == ConfigurationScope.SYSTEM) actor.requireAdmin();
        return catalogService.clearBinding(actor.userId(), scopeType, purpose);
    }

    private static boolean isSystemScope(BindingRequest request) {
        return request.scope() != null && "SYSTEM".equalsIgnoreCase(request.scope());
    }

    private static ConfigurationScope scopeOf(BindingRequest request) {
        return isSystemScope(request) ? ConfigurationScope.SYSTEM : ConfigurationScope.USER;
    }

    private static ConfigurationScope parseScope(String scope) {
        return "SYSTEM".equalsIgnoreCase(scope) ? ConfigurationScope.SYSTEM : ConfigurationScope.USER;
    }
}
