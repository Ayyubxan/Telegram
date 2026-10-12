package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.finance.Money;
import org.telegram.ui.habits.HabitUi;

/** FORK MOD (Finance): grouped bars per month — income (green) and expenses (red) */
public class BarsView extends View {
    private String[] labels = new String[0];
    private double[] income = new double[0], expense = new double[0];
    private String code = "UZS";
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public BarsView(Context ctx) {
        super(ctx);
        text.setTextSize(dp(11));
        text.setTypeface(AndroidUtilities.bold());
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(String[] labels, double[] income, double[] expense, String code) {
        this.labels = labels; this.income = income; this.expense = expense; this.code = code;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = labels.length;
        if (n == 0) return;
        double max = 1e-9;
        for (int i = 0; i < n; i++) max = Math.max(max, Math.max(income[i], expense[i]));
        float top = dp(18), bottom = getHeight() - dp(18), left = dp(4), right = getWidth() - dp(4);
        float slot = (right - left) / n;
        float barW = Math.min(dp(14), slot * 0.32f);
        paint.setColor(HabitUi.withAlpha(HabitUi.sub(), 0x30));
        c.drawRect(left, bottom, right, bottom + 1, paint);
        text.setColor(HabitUi.sub());
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(Money.compact(max, code), left, top - dp(5), text);
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < n; i++) {
            float cx = left + slot * i + slot / 2;
            float hi = (float) (income[i] / max * (bottom - top));
            float he = (float) (expense[i] / max * (bottom - top));
            paint.setColor(FinanceUi.income());
            rect.set(cx - barW - dp(1.5f), bottom - Math.max(hi, dp(2)), cx - dp(1.5f), bottom);
            c.drawRoundRect(rect, dp(3), dp(3), paint);
            paint.setColor(FinanceUi.expense());
            rect.set(cx + dp(1.5f), bottom - Math.max(he, dp(2)), cx + barW + dp(1.5f), bottom);
            c.drawRoundRect(rect, dp(3), dp(3), paint);
            c.drawText(labels[i], cx, bottom + dp(13), text);
        }
    }
}
