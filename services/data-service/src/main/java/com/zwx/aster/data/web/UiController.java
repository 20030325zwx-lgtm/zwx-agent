package com.zwx.aster.data.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 独立管理页入口：/data/ 转发到打包进 jar 的静态 index.html。 */
@Controller
public class UiController {

    @GetMapping({"/data", "/data/"})
    public String index() {
        return "forward:/data/index.html";
    }
}
