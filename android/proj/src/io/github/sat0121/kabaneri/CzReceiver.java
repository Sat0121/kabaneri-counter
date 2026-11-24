package io.github.sat0121.kabaneri;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 通知のボタンが押されたときの受け口。アプリが動いていればカウンターに直接記録し、いなければ保存しておく。 */
public class CzReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!CzNotify.ACTION.equals(intent.getAction())) return;
        String op = intent.getStringExtra("op"), t = intent.getStringExtra("t"), type = intent.getStringExtra("type");
        if (op == null) return;
        if ("close".equals(op)) {
            CzNotify.close(context);
            return;
        }
        if (!"add".equals(op) && !"high".equals(op) && !"enter".equals(op) && !"undo".equals(op)) return;
        MainActivity a = MainActivity.live();
        if (a != null && a.counterReady()) a.notifyOp(op, t, type);
        else CzNotify.queue(context.getApplicationContext(), op, t, type);
    }
}
