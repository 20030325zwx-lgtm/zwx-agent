package com.zwx.aster.eval.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 独立管理页入口：/eval/ 转发到打包进 jar 的静态 index.html。 */
@Controller
public class UiController {

    @GetMapping({"/eval", "/eval/"})
    public String index() {
        return "forward:/eval/index.html";
    }
}
