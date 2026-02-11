package com.xu.xuojbackendjudgeservice.judge.codesandbox;

import com.xu.xuojbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.xu.xuojbackendmodel.model.codesandbox.ExecuteCodeResponse;

/**
 * 代码沙箱接口定义
 */
public interface CodeSandbox {

    /**
     * 执行代码
     *
     * @param executeCodeRequest
     * @return
     */
    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);
}
