package io.github.aw1y2z.sesame.model.task.taobaoFarm;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.aw1y2z.sesame.data.Model;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.modelFieldExt.EmptyModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.IntegerModelField;
import io.github.aw1y2z.sesame.hook.TaobaoApplicationHook;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 淘宝芭芭农场（天猫农场）自动任务模块。
 * 通过 MTOP 协议调用 mtop.tmall.farm.* 接口完成浇水、收菜、做任务等。
 */
public class TaobaoFarm extends Model {

    private static final String TAG = "TaobaoFarm";

    private final IntegerModelField waterCount =
            new IntegerModelField("tbFarmWaterCount", "淘宝农场 | 每日浇水次数上限", 10, 0, 50);

    private final EmptyModelField waterNow =
            new EmptyModelField("tbFarmWaterNow", "淘宝农场 | 立即浇水", this::doWater);

    private final EmptyModelField doTasksNow =
            new EmptyModelField("tbFarmDoTasks", "淘宝农场 | 立即做任务", this::doTasks);

    @Override
    public String getName() {
        return "淘宝农场";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.OTHER;
    }

    @Override
    public ModelFields getFields() {
        ModelFields fields = new ModelFields();
        fields.addField(waterCount);
        fields.addField(waterNow);
        fields.addField(doTasksNow);
        return fields;
    }

    @Override
    public void boot(ClassLoader classLoader) {
        Log.i(TAG, "TaobaoFarm boot, taobaoHooked=" + TaobaoApplicationHook.isHooked());
    }

    /** 立即浇水 */
    private void doWater() {
        if (!TaobaoApplicationHook.isHooked()) {
            Log.record("淘宝未hook，请先打开淘宝App");
            return;
        }
        int count = waterCount.getValue();
        int watered = 0;
        for (int i = 0; i < count; i++) {
            String res = TaobaoFarmRpcCall.watering();
            if (res != null) {
                try {
                    JSONObject jo = new JSONObject(res);
                    if (jo.optBoolean("success")) {
                        watered++;
                        Log.farm("淘宝农场💧浇水[" + (i + 1) + "/" + count + "]");
                    } else {
                        String msg = jo.optString("errorMsg", jo.optString("msg", "未知"));
                        Log.farm("淘宝农场💧浇水停止: " + msg);
                        break;
                    }
                } catch (Exception e) {
                    Log.farm("淘宝农场💧浇水响应解析失败: " + res);
                    break;
                }
            } else {
                Log.farm("淘宝农场💧浇水请求失败(超时/未hook)");
                break;
            }
            TimeUtil.sleep(2000 + (int) (Math.random() * 1000));
        }
        Log.farm("淘宝农场💧浇水完成[" + watered + "/" + count + "]");
    }

    /** 立即做任务 */
    private void doTasks() {
        if (!TaobaoApplicationHook.isHooked()) {
            Log.record("淘宝未hook，请先打开淘宝App");
            return;
        }
        String res = TaobaoFarmRpcCall.taskList();
        if (res == null) {
            Log.farm("淘宝农场📋获取任务列表失败");
            return;
        }
        try {
            JSONObject jo = new JSONObject(res);
            JSONArray taskArray = jo.optJSONArray("taskList");
            if (taskArray == null || taskArray.length() == 0) {
                Log.farm("淘宝农场📋没有可用任务");
                return;
            }
            for (int i = 0; i < taskArray.length(); i++) {
                JSONObject task = taskArray.getJSONObject(i);
                String status = task.optString("taskStatus", task.optString("status", ""));
                String taskId = task.optString("taskId", "");
                String taskType = task.optString("taskType", "");
                String title = task.optString("title", taskId);

                if ("TODO".equalsIgnoreCase(status) || "PENDING".equalsIgnoreCase(status)) {
                    String doRes = TaobaoFarmRpcCall.doTask(taskId, taskType);
                    if (doRes != null) {
                        JSONObject doJo = new JSONObject(doRes);
                        if (doJo.optBoolean("success")) {
                            Log.farm("淘宝农场🧾完成任务[" + title + "]");
                        }
                    }
                    TimeUtil.sleep(1500);
                } else if ("FINISHED".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) {
                    String recvRes = TaobaoFarmRpcCall.taskReceive(taskId);
                    if (recvRes != null) {
                        JSONObject recvJo = new JSONObject(recvRes);
                        if (recvJo.optBoolean("success")) {
                            Log.farm("淘宝农场🎖️领取[" + title + "]");
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "doTasks err:", t);
        }
    }
}
