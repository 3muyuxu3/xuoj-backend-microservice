package com.xu.xuojbackendjudgeservice.judge.codesandbox.impl;

import com.xu.xuojbackendjudgeservice.judge.codesandbox.CodeSandbox;
import com.xu.xuojbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.xu.xuojbackendmodel.model.codesandbox.ExecuteCodeResponse;

/**
 * 第三方代码沙箱（调用网上现成的代码沙箱）
 */
public class ThirdPartyCodeSandbox implements CodeSandbox {
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        System.out.println("第三方代码沙箱");
        return null;
    }
}
