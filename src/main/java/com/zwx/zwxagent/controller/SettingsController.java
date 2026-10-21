package com.zwx.zwxagent.controller;

import com.zwx.zwxagent.configuration.AppConfigurationService;
import com.zwx.zwxagent.configuration.ConfigurationScope;
import com.zwx.zwxagent.configuration.ModelConfigurationProbe;
import com.zwx.zwxagent.configuration.ModelConfigurationRequest;
import com.zwx.zwxagent.configuration.ModelConfigurationView;
import com.zwx.zwxagent.configuration.ModelTestResult;
import com.zwx.zwxagent.security.CurrentActor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/settings")
public class SettingsController {

    private final AppConfigurationService configurationService;
    private final ModelConfigurationProbe modelConfigurationProbe;

    public SettingsController(AppConfigurationService configurationService,
                              ModelConfigurationProbe modelConfigurationProbe) {
        this.configurationService = configurationService;
        this.modelConfigurationProbe = modelConfigurationProbe;
    }

    @GetMapping("/models")
    public Map<String, Object> listModels(CurrentActor actor) {
        List<ModelConfigurationView> models = configurationService.list(actor.userId());
        return Map.of("canManageSystem", actor.isAdmin(), "models", models);
    }

    @PutMapping("/models/{key}")
    public ModelConfigurationView saveUserModel(CurrentActor actor, @PathVariable String key,
                                                @RequestBody ModelConfigurationRequest request) {
        return configurationService.save(actor.userId(), ConfigurationScope.USER, key, request);
    }

    @DeleteMapping("/models/{key}")
    public Map<String, Object> resetUserModel(CurrentActor actor, @PathVariable String key) {
        configurationService.reset(actor.userId(), ConfigurationScope.USER, key);
        return Map.of("reset", true);
    }

    @PutMapping("/system/models/{key}")
    public ModelConfigurationView saveSystemModel(CurrentActor actor, @PathVariable String key,
                                                  @RequestBody ModelConfigurationRequest request) {
        actor.requireAdmin();
        return configurationService.save(actor.userId(), ConfigurationScope.SYSTEM, key, request);
    }

    @DeleteMapping("/system/models/{key}")
    public Map<String, Object> resetSystemModel(CurrentActor actor, @PathVariable String key) {
        actor.requireAdmin();
        configurationService.reset(actor.userId(), ConfigurationScope.SYSTEM, key);
        return Map.of("reset", true);
    }

    @PostMapping("/models/{key}/test")
    public ModelTestResult testModel(CurrentActor actor, @PathVariable String key,
                                     @RequestParam(defaultValue = "USER") String scope) {
        if ("SYSTEM".equalsIgnoreCase(scope)) {
            actor.requireAdmin();
            return modelConfigurationProbe.test(configurationService.resolveSystem(key));
        }
        return modelConfigurationProbe.test(configurationService.resolve(actor.userId(), key));
    }
}
