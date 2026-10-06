package io.github.aw1y2z.sesame.model.task.taobaoFarm;

import io.github.aw1y2z.sesame.hook.TaobaoApplicationHook;

public class TaobaoFarmRpcCall {

    private static final String V = "1.0";

    /** 获取农场主页（果树状态、任务列表、奖励等） */
    public static String mainGet() {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.main.get", V, "{}");
    }

    /** 获取额外数据（活动、礼包、问答等） */
    public static String extraGet() {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.extra.get", V, "{}");
    }

    /** 浇水 */
    public static String watering() {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.plant.watering", V, "{}");
    }

    /** 开箱子 */
    public static String openBox() {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.plant.openbox", V, "{}");
    }

    /** 用户农场信息 */
    public static String userMainFarm() {
        return TaobaoApplicationHook.requestString("mtop.tmall.jiuxi.activity.farm.user.getmainfarm", V, "{}");
    }

    /** 收集能量 */
    public static String energyCollect(String energyId) {
        return TaobaoApplicationHook.requestString("mtop.tmall.jiuxi.activity.farm.energy.collect", V,
                "{\"energyId\":\"" + energyId + "\"}");
    }

    /** 执行任务（浏览/点击/搜索等） */
    public static String doTask(String taskId) {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.task.do", V,
                "{\"taskId\":\"" + taskId + "\"}");
    }

    /** 领取任务奖励 */
    public static String taskReceive(String taskId) {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.task.receive", V,
                "{\"taskId\":\"" + taskId + "\"}");
    }

    /** 领取肥料礼包 */
    public static String receiveGift() {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.gift.receive", V, "{}");
    }

    /** 问答 - 获取题目 */
    public static String quizGet() {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.quiz.get", V, "{}");
    }

    /** 问答 - 提交答案 */
    public static String quizSubmit(String questionId, String answer) {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.quiz.submit", V,
                "{\"questionId\":\"" + questionId + "\",\"answer\":\"" + answer + "\"}");
    }
}
