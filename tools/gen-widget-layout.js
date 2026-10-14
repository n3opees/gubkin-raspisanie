// Генерирует app/res/layout/widget.xml: шапка + 9 одинаковых строк пар.
// В виджетах Android можно использовать только ограниченный набор элементов
// (LinearLayout, TextView), поэтому полоска типа пары — это TextView с цветным фоном.
// Запуск: node tools/gen-widget-layout.js
const fs = require("fs");
const path = require("path");
const ROWS = 9;

const row = (i) => `
        <LinearLayout
            android:id="@+id/w_row${i}"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="5dp"
            android:orientation="horizontal"
            android:visibility="gone">
            <TextView
                android:id="@+id/w_bar${i}"
                android:layout_width="3dp"
                android:layout_height="match_parent"
                android:background="#8A8F98" />
            <LinearLayout
                android:layout_width="46dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:orientation="vertical">
                <TextView
                    android:id="@+id/w_start${i}"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="#FFFFFF"
                    android:textSize="12sp"
                    android:textStyle="bold" />
                <TextView
                    android:id="@+id/w_end${i}"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="#8A8F98"
                    android:textSize="10sp" />
            </LinearLayout>
            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="vertical">
                <TextView
                    android:id="@+id/w_name${i}"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:ellipsize="end"
                    android:maxLines="1"
                    android:textColor="#FFFFFF"
                    android:textSize="12sp"
                    android:textStyle="bold" />
                <TextView
                    android:id="@+id/w_sub${i}"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:ellipsize="end"
                    android:maxLines="1"
                    android:textColor="#8A8F98"
                    android:textSize="10sp" />
            </LinearLayout>
        </LinearLayout>`;

const xml = `<?xml version="1.0" encoding="utf-8"?>
<!-- СГЕНЕРИРОВАНО tools/gen-widget-layout.js — правь генератор, а не этот файл -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/w_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg"
    android:orientation="vertical"
    android:padding="14dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="bottom"
        android:orientation="horizontal">
        <TextView
            android:id="@+id/w_title"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="Сегодня"
            android:textColor="#FFFFFF"
            android:textSize="15sp"
            android:textStyle="bold" />
        <TextView
            android:id="@+id/w_date"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="#8A8F98"
            android:textSize="11sp" />
    </LinearLayout>

    <TextView
        android:id="@+id/w_note"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:textColor="#E0A43B"
        android:textSize="9sp"
        android:visibility="gone" />

    <TextView
        android:id="@+id/w_empty"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="10dp"
        android:text="Открой приложение и войди в кабинет."
        android:textColor="#8A8F98"
        android:textSize="13sp" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="3dp"
        android:orientation="vertical">${[...Array(ROWS).keys()].map(row).join("")}
    </LinearLayout>
</LinearLayout>
`;

const out = path.join(__dirname, "..", "app", "res", "layout", "widget.xml");
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, xml);
console.log("written", out, "rows:", ROWS);
