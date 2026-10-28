package local.agentos;

import android.app.Activity;
import android.os.Bundle;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;

/** Fictitious feed, installed only with the test APK. No accounts, network or real data. */
public class ModFixture extends Activity {
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (getIntent().getBooleanExtra("crash", false)) new android.os.Handler().postDelayed(() -> { throw new IllegalStateException("mod watchdog fixture"); }, 750);
        ListView list = new ListView(this);
        list.setAdapter(new BaseAdapter() {
            public int getCount() { return 12; }
            public Object getItem(int p) { return p; }
            public long getItemId(int p) { return p; }
            public View getView(int p, View old, ViewGroup parent) {
                LinearLayout row = new LinearLayout(ModFixture.this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(24, 24, 24, 24);
                TextView label = new TextView(ModFixture.this); label.setText(p == 1 ? "Promoted" : "Organic post " + p); label.setTextSize(20); row.addView(label);
                TextView content = new TextView(ModFixture.this); content.setText("Fictitious feed item\n\nNothing here is sent or saved."); content.setTextSize(18); row.addView(content);
                return row;
            }
        });
        setContentView(list);
    }
}
