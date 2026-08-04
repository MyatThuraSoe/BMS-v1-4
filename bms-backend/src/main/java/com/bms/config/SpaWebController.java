package com.bms.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class SpaWebController {

    // Forwards all non-API, non-static routes to index.html so React Router can handle them
    @RequestMapping(value = {
            "/{path:[^\\.]*}",
            "/{path:[^\\.]*}/{subpath:[^\\.]*}",
            "/{path:[^\\.]*}/{subpath:[^\\.]*}/{remaining:[^\\.]*}"
    })
    public String forward() {
        return "forward:/index.html";
    }
}