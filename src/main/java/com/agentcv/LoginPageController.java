package com.agentcv;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** Local, same-origin login page. Never stores or logs a submitted password. */
@Controller
class LoginPageController {
    @GetMapping(value = "/login", produces = "text/html;charset=UTF-8")
    @ResponseBody
    String login() {
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"><title>登录 · AgentCV</title>
                <style>body{margin:0;background:#f4f6fa;color:#203044;font:16px/1.6 system-ui,sans-serif}
                main{max-width:360px;margin:10vh auto;padding:32px;background:white;border:1px solid #dce3eb;border-radius:12px}
                label{display:block;margin-top:18px}input,button{box-sizing:border-box;width:100%;padding:12px;font:inherit;border:1px solid #9cabbc;border-radius:6px}
                button{margin-top:24px;background:#235bc1;color:white;cursor:pointer}button:disabled{opacity:.6}
                [role=alert]{color:#a02626;min-height:26px}small{color:#52647a}</style></head>
                <body><main><h1>登录 AgentCV</h1><p>本机简历工作台</p>
                <form id="login"><label for="username">用户名</label><input id="username" name="username" value="operator" autocomplete="username" required>
                <label for="password">密码</label><input id="password" name="password" type="password" autocomplete="current-password" required>
                <p role="alert" id="error" aria-live="polite"></p><button type="submit">登录</button></form>
                <p><small>忘记密码？在 WSL 运行项目的 set-password 命令，设置后重启应用。请勿把密码发送给他人。</small></p></main>
                <script>
                const form=document.querySelector('form'),button=form.querySelector('button'),error=document.querySelector('#error');
                form.addEventListener('submit',async event=>{event.preventDefault();button.disabled=true;error.textContent='';
                try{const csrfResponse=await fetch('/api/v1/csrf',{cache:'no-store'});
                if(!csrfResponse.ok)throw Error('无法取得登录凭据，请稍后重试。');const csrf=await csrfResponse.json();
                const body=new URLSearchParams(new FormData(form));body.set(csrf.parameterName||'_csrf',csrf.token);
                const response=await fetch('/login',{method:'POST',headers:{[csrf.headerName]:csrf.token},body});
                if(response.status===429){error.textContent='登录请求过于频繁，请等待一分钟后重试。';}
                else if(response.ok&&new URL(response.url).pathname==='/'){location.assign('/');return;}
                else{error.textContent='用户名或密码不正确，请检查后重试。';}
                }catch(e){error.textContent='无法连接服务，请检查应用是否运行后重试。';}
                finally{button.disabled=false;}});
                </script></body></html>
                """;
    }
}