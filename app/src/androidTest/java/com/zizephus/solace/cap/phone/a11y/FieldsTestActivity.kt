package com.zizephus.solace.cap.phone.a11y

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 读屏与输入核实的测试页:
 * - 「结果」文字被标成对无障碍不重要(像计算器的显示区),按「7」只改它;
 * - 「空输入框」点一下只换输入焦点;
 * - 「热词示例」是只显示提示语的搜索框;
 * - 「会换的框」输入后被换成一个新的输入控件(有些 App 内容一变就重建输入视图);
 * - 「假搜索框」只是一段可点的文字,点了才出现真正的输入框并拿到焦点(很多首页的搜索条就是这样)。
 */
class FieldsTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val result = TextView(this).apply {
            text = "0"; textSize = 32f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(result)
        root.addView(Button(this).apply { text = "7"; setOnClickListener { result.append("7") } })
        root.addView(EditText(this).apply { hint = "空输入框"; contentDescription = null })
        root.addView(EditText(this).apply { hint = "热词示例" })
        val holder = LinearLayout(this)
        root.addView(holder)
        fun swapping(initial: CharSequence): EditText = EditText(this).apply {
            hint = "会换的框"
            setText(initial)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    if (text.isEmpty()) return
                    post {
                        holder.removeAllViews()
                        val next = swapping(text)
                        holder.addView(next, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        next.requestFocus()
                    }
                }
            })
        }
        holder.addView(swapping(""), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val real = EditText(this).apply { hint = "真正的输入框"; visibility = View.GONE }
        root.addView(TextView(this).apply {
            text = "假搜索框"; textSize = 24f
            setOnClickListener { real.visibility = View.VISIBLE; real.requestFocus() }
        })
        root.addView(real)
        setContentView(root)
    }
}
