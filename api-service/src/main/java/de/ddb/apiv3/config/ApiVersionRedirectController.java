/* 
 *  Copyright 2026 Michael Büchner, Deutsche Nationalbibliothek
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *      https://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  
 *  This code was made with help from OpenAI GTP-6.1 Sol Ultra.
 */

package de.ddb.apiv3.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.view.RedirectView;

/** Resource IRIs have no API version; HTTP dereferencing enters the current API. */
@Controller
public final class ApiVersionRedirectController {

    // Keep framework namespaces and already-versioned URLs outside the resource rule.
    private static final String RESOURCE_PATH =
            "/{resource:(?!(?:actuator|openapi|webjars|swagger-ui|error|contexts)$)[a-z][a-z0-9-]*}/**";

    @RequestMapping(RESOURCE_PATH)
    public RedirectView redirectResource(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        var redirect = new RedirectView("/3" + path, true);
        redirect.setStatusCode(HttpStatus.PERMANENT_REDIRECT);
        redirect.setPropagateQueryParams(true);
        redirect.setExpandUriTemplateVariables(false);
        redirect.setExposeModelAttributes(false);
        return redirect;
    }
}
