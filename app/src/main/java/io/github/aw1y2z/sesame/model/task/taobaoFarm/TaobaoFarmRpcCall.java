package io.github.aw1y2z.sesame.model.task.taobaoFarm;

import io.github.aw1y2z.sesame.hook.TaobaoApplicationHook;

/**
 * 淘宝芭芭农场（天猫农场）MTOP API 调用封装。
 * 接口前缀: mtop.tmall.farm.*
 */
public class TaobaoFarmRpcCall {

    private static final String V = "1.0";

    /** 获取农场主页信息（果树状态、进度、任务列表等） */
    public static String mainGet() {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.main.get", V, "{}");
    }

    /** 获取额外信息（任务详情、活动列表等） */
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

    /** 收菜/收获 */
    public static String harvest(String orchardId) {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.plant.harvest", V,
                "{\"orchardId\":\"" + orchardId + "\"}");
    }

    /** 用户农场主信息 */
    public static String userMainFarm() {
        return TaobaoApplicationHook.requestString("mtop.tmall.jiuxi.activity.farm.user.getmainfarm", V, "{}");
    }

    /** 收集能量 */
    public static String energyCollect(String energyId) {
        return TaobaoApplicationHook.requestString("mtop.tmall.jiuxi.activity.farm.energy.collect", V,
                "{\"energyId\":\"" + energyId + "\"}");
    }

    /** 任务列表 */
    public static String taskList() {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.task.list", V, "{}");
    }

    /** 执行任务 */
    public static String doTask(String taskId, String taskType) {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.task.do", V,
                "{\"taskId\":\"" + taskId + "\",\"taskType\":\"" + taskType + "\"}");
    }

    /** 领取任务奖励 */
    public static String taskReceive(String taskId) {
        return TaobaoApplicationHook.requestString("mtop.tmall.farm.orchard.task.receive", V,
                "{\"taskId\":\"" + taskId + "\"}");
    }
}
