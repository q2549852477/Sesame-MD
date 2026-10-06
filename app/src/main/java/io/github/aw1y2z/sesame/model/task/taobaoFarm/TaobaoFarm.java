package io.github.aw1y2z.sesame.model.task.taobaoFarm;

import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.aw1y2z.sesame.data.Model;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.ModelGroup;
import io.github.aw1y2z.sesame.data.modelFieldExt.EmptyModelField;
import io.github.aw1y2z.sesame.data.modelFieldExt.IntegerModelField;
import io.github.aw1y2z.sesame.hook.TaobaoApplicationHook;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.TimeUtil;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 淘宝芭芭农场自动任务模块。
 * 支持：浇水、开箱子、领礼包、浏览任务（含滑动）、点击任务、答题。
 * 跳转类任务自动跳过。
 */
public class TaobaoFarm extends Model {

    private static final String TAG = "TaobaoFarm";

    /** 跳转/不可自动完成的任务关键词 */
    private static final String[] SKIP_KEYWORDS = {
            "闲鱼", "美团", "微博", "头条", "快手", "大众点评", "中国移动",
            "菜鸟", "趣头条", "三国冰河", "淘宝特价版", "88VIP",
            "邀请", "邀TA", "支付宝", "红包签到", "外卖红包"
    };

    /** 匹配 "浏览15秒" "浏览10s" "浏览5秒" "玩30秒" 等 */
    private static final Pattern BROWSE_SEC_PATTERN = Pattern.compile(
            "(?:浏览|停留|观看|玩)\\s*(\\d+)\\s*(?:秒|s|S)");

    /** 匹配 "点击3个商品" "点击N个" */
    private static final Pattern CLICK_N_PATTERN = Pattern.compile(
            "点击\\s*(\\d+)\\s*(?:个|次)");

    private final IntegerModelField waterCount =
            new IntegerModelField("tbFarmWaterCount", "淘宝农场 | 每日浇水次数", 10, 0, 50);

    private final IntegerModelField browseSwipes =
            new IntegerModelField("tbFarmBrowseSwipes", "淘宝农场 | 每次浏览滑动次数", 3, 1, 10);

    private final EmptyModelField waterNow =
            new EmptyModelField("tbFarmWaterNow", "淘宝农场 | 立即浇水", this::doWater);

    private final EmptyModelField doTasksNow =
            new EmptyModelField("tbFarmDoTasks", "淘宝农场 | 立即做全部任务", this::doAllTasks);

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
        fields.addField(browseSwipes);
        fields.addField(waterNow);
        fields.addField(doTasksNow);
        return fields;
    }

    @Override
    public void boot(ClassLoader classLoader) {
        Log.i(TAG, "TaobaoFarm boot, taobaoHooked=" + TaobaoApplicationHook.isHooked());
    }

    // ==================== 主流程 ====================

    private void doAllTasks() {
        if (!TaobaoApplicationHook.isHooked()) {
            Log.record("淘宝未hook，请先打开淘宝App");
            return;
        }
        Log.farm("淘宝农场🚀开始执行全部任务");

        doWater();
        doOpenBox();
        doReceiveGift();
        doTasks();

        Log.farm("淘宝农场✅全部任务执行完毕");
    }

    // ==================== 浇水 ====================

    private void doWater() {
        int count = waterCount.getValue();
        if (count <= 0) return;
        int watered = 0;
        for (int i = 0; i < count; i++) {
            String res = TaobaoFarmRpcCall.watering();
            if (res == null) {
                Log.farm("淘宝农场💧浇水请求失败");
                break;
            }
            try {
                JSONObject jo = parseData(res);
                if (isSuccess(jo)) {
                    watered++;
                    Log.farm("淘宝农场💧浇水[" + (i + 1) + "/" + count + "]");
                } else {
                    Log.farm("淘宝农场💧浇水停止: " + errorMsg(jo));
                    break;
                }
            } catch (Exception e) {
                Log.farm("淘宝农场💧浇水响应异常");
                break;
            }
            TimeUtil.sleep(2000 + (int) (Math.random() * 1000));
        }
        if (watered > 0) Log.farm("淘宝农场💧浇水完成[" + watered + "/" + count + "]");
    }

    // ==================== 开箱子 ====================

    private void doOpenBox() {
        String res = TaobaoFarmRpcCall.openBox();
        if (res != null) {
            try {
                JSONObject jo = parseData(res);
                if (isSuccess(jo)) {
                    Log.farm("淘宝农场📦开箱子成功");
                } else {
                    Log.farm("淘宝农场📦无箱子: " + errorMsg(jo));
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 领礼包 ====================

    private void doReceiveGift() {
        String res = TaobaoFarmRpcCall.receiveGift();
        if (res != null) {
            try {
                JSONObject jo = parseData(res);
                if (isSuccess(jo)) {
                    Log.farm("淘宝农场🎁领取肥料礼包成功");
                } else {
                    Log.farm("淘宝农场🎁礼包不可领: " + errorMsg(jo));
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 做任务 ====================

    private void doTasks() {
        String res = TaobaoFarmRpcCall.mainGet();
        if (res == null) {
            Log.farm("淘宝农场📋获取主页失败");
            return;
        }

        JSONArray taskArray = extractTaskArray(res);
        if (taskArray == null || taskArray.length() == 0) {
            Log.farm("淘宝农场📋没有任务");
            return;
        }

        int done = 0, skipped = 0, failed = 0;

        for (int i = 0; i < taskArray.length(); i++) {
            try {
                JSONObject task = taskArray.getJSONObject(i);
                String taskId = firstNonNull(task, "taskId", "id", "taskCode", "task_id");
                if (taskId == null || taskId.isEmpty()) continue;

                String title = firstNonNull(task, "title", "taskName", "name", "desc", taskId);
                String status = firstNonNull(task, "taskStatus", "status", "state", "TODO");

                // 已完成/已领取 → 尝试领取奖励
                if ("FINISHED".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)
                        || "RECEIVABLE".equalsIgnoreCase(status)) {
                    String recvRes = TaobaoFarmRpcCall.taskReceive(taskId);
                    if (recvRes != null && isSuccess(parseData(recvRes))) {
                        done++;
                        Log.farm("淘宝农场🎖️领取[" + title + "]");
                    }
                    TimeUtil.sleep(800);
                    continue;
                }

                // 已DONE → 跳过
                if ("DONE".equalsIgnoreCase(status) || "RECEIVED".equalsIgnoreCase(status)) {
                    skipped++;
                    continue;
                }

                // 跳转类 → 跳过
                if (isSkipTask(title, task)) {
                    skipped++;
                    continue;
                }

                // 执行任务：判断类型
                int browseSec = parseBrowseSeconds(title);
                int clickCount = parseClickCount(title);

                if (clickCount > 0) {
                    // 点击类任务
                    boolean ok = doClickTask(taskId, clickCount);
                    if (ok) {
                        done++;
                        Log.farm("淘宝农场👆点击[" + title + "] x" + clickCount);
                    } else {
                        failed++;
                    }
                } else if (browseSec > 0) {
                    // 浏览类任务：需要滑动浏览
                    boolean ok = doBrowseTask(taskId, browseSec);
                    if (ok) {
                        done++;
                        Log.farm("淘宝农场👁️浏览[" + title + "] " + browseSec + "s");
                    } else {
                        failed++;
                    }
                } else {
                    // 通用任务（答题/领取等）：直接 doTask
                    String doRes = TaobaoFarmRpcCall.doTask(taskId);
                    if (doRes != null && isSuccess(parseData(doRes))) {
                        done++;
                        Log.farm("淘宝农场🧾完成[" + title + "]");
                    } else {
                        failed++;
                    }
                }
                TimeUtil.sleep(1500 + (int) (Math.random() * 1500));
            } catch (Throwable t) {
                failed++;
            }
        }

        Log.farm("淘宝农场📋汇总: 完成" + done + " 跳过" + skipped + " 失败" + failed + " / 总" + taskArray.length());
    }

    // ==================== 浏览任务（含滑动） ====================

    /**
     * 浏览任务：实际滑动浏览 N 秒，结束后调 doTask 通知服务端。
     */
    private boolean doBrowseTask(String taskId, int seconds) {
        View target = TaobaoApplicationHook.getSwipeTarget();
        if (target == null) {
            Log.i(TAG, "browse: no swipe target");
            return false;
        }

        int swipeCount = browseSwipes.getValue();
        long totalMs = seconds * 1000L;
        long intervalMs = totalMs / Math.max(1, swipeCount);
        long start = System.currentTimeMillis();

        CountDownLatch browseDone = new CountDownLatch(1);
        new Thread(() -> {
            for (int s = 0; s < swipeCount; s++) {
                long remaining = totalMs - (System.currentTimeMillis() - start);
                if (remaining <= 0) break;
                TaobaoApplicationHook.swipeUp(target);
                try {
                    Thread.sleep(Math.min(intervalMs, remaining));
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            browseDone.countDown();
        }, "tb-farm-browse").start();

        try {
            browseDone.await(totalMs + 5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }

        String doRes = TaobaoFarmRpcCall.doTask(taskId);
        return doRes != null && isSuccess(parseDataSafe(doRes));
    }

    // ==================== 点击任务 ====================

    /**
     * 点击任务：模拟点击 N 次（间隔随机），然后 doTask。
     */
    private boolean doClickTask(String taskId, int clickCount) {
        View target = TaobaoApplicationHook.getSwipeTarget();
        if (target == null) {
            Log.i(TAG, "click: no target view");
            return false;
        }

        for (int i = 0; i < clickCount; i++) {
            TaobaoApplicationHook.tap(target);
            try {
                Thread.sleep(800 + (int) (Math.random() * 700));
            } catch (InterruptedException ignored) {
                break;
            }
        }

        String doRes = TaobaoFarmRpcCall.doTask(taskId);
        return doRes != null && isSuccess(parseDataSafe(doRes));
    }

    // ==================== 解析工具 ====================

    /** 从标题解析浏览秒数，如 "浏览15秒得+700" → 15 */
    private int parseBrowseSeconds(String title) {
        if (title == null) return 0;
        Matcher m = BROWSE_SEC_PATTERN.matcher(title);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        // 也检查 desc/subTitle 字段在 task JSON 中（由调用方处理）
        return 0;
    }

    /** 从标题解析点击次数，如 "点击3个商品" → 3 */
    private int parseClickCount(String title) {
        if (title == null) return 0;
        Matcher m = CLICK_N_PATTERN.matcher(title);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /** 判断是否跳转类任务 */
    private boolean isSkipTask(String title, JSONObject task) {
        if (title != null) {
            for (String kw : SKIP_KEYWORDS) {
                if (title.contains(kw)) return true;
            }
        }
        // 检查 actionUrl 是否指向外部
        String url = firstNonNull(task, "actionUrl", "jumpUrl", "targetUrl", "link", "scheme");
        if (url != null && !url.isEmpty()
                && !url.contains("tmall.com") && !url.contains("taobao.com")
                && !url.contains("mtop")) {
            return true;
        }
        return false;
    }

    // ==================== JSON 工具 ====================

    private JSONArray extractTaskArray(String response) {
        try {
            JSONObject root = new JSONObject(response);
            JSONObject data = root.optJSONObject("data");
            if (data == null) data = root;

            String[] paths = {"taskList", "task_list", "tasks", "taskListVO", "taskVOList", "taskInfoList"};
            for (String key : paths) {
                JSONArray arr = data.optJSONArray(key);
                if (arr != null && arr.length() > 0) return arr;
            }
            // 嵌套在 activityList 中
            JSONArray actList = data.optJSONArray("activityList");
            if (actList != null) {
                for (int i = 0; i < actList.length(); i++) {
                    JSONObject act = actList.getJSONObject(i);
                    JSONArray sub = act.optJSONArray("taskList");
                    if (sub != null && sub.length() > 0) return sub;
                }
            }
            // 打印 key 帮助调试
            StringBuilder keys = new StringBuilder();
            java.util.Iterator<String> it = data.keys();
            while (it.hasNext()) keys.append(it.next()).append(", ");
            Log.i(TAG, "main.get data keys: " + keys);
        } catch (Throwable t) {
            Log.i(TAG, "extractTaskArray err: " + t.getMessage());
        }
        return null;
    }


    private JSONObject parseData(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        return root.optJSONObject("data") != null ? root.getJSONObject("data") : root;
    }

    private JSONObject parseDataSafe(String response) {
        try {
            return parseData(response);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private boolean isSuccess(JSONObject jo) {
        if (jo == null) return false;
        Boolean s = jo.optBoolean("success", null);
        if (s != null) return s;
        String code = jo.optString("code", jo.optString("retCode", ""));
        return "SUCCESS".equalsIgnoreCase(code) || "200".equals(code);
    }

    private String errorMsg(JSONObject jo) {
        if (jo == null) return "null";
        return jo.optString("errorMsg", jo.optString("msg", jo.optString("retMsg", "unknown")));
    }

    private String firstNonNull(JSONObject jo, String... keys) {
        for (String key : keys) {
            String v = jo.optString(key, null);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }
}
