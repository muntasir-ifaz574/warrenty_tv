package com.lumit.warrantyactivator

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		UsageTrackerService.start(this)
		val textView = TextView(this)
		textView.text = "Warranty Activator"
		textView.textSize = 22f
		setContentView(textView)
	}
}


