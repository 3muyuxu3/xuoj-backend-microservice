package com.xu.xuojbackendjudgeservice.judge;

import com.xu.xuojbackendjudgeservice.judge.strategy.DefaultJudgeStrategy;
import com.xu.xuojbackendjudgeservice.judge.strategy.JavaLanguageJudgeStrategy;
import com.xu.xuojbackendjudgeservice.judge.strategy.JudgeContext;
import com.xu.xuojbackendjudgeservice.judge.strategy.JudgeStrategy;
import com.xu.xuojbackendmodel.model.codesandbox.JudgeInfo;
import com.xu.xuojbackendmodel.model.entity.QuestionSubmit;
import org.springframework.stereotype.Service;

/**
 * 判题管理（简化调用）
 */
@Service
public class JudgeManager {

    /**
     * 执行判题
     *
     * @param judgeContext
     * @return
     */
    JudgeInfo doJudge(JudgeContext judgeContext) {
        QuestionSubmit questionSubmit = judgeContext.getQuestionSubmit();
        String language = questionSubmit.getLanguage();
        JudgeStrategy judgeStrategy = new DefaultJudgeStrategy();
        if ("java".equals(language)) {
            judgeStrategy = new JavaLanguageJudgeStrategy();
        }
        return judgeStrategy.doJudge(judgeContext);
    }

}
