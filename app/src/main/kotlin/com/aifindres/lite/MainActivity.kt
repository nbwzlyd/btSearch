package com.aifindres.lite

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.aifindres.lite.ui.MagnetFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_KEYWORD = "keyword"
        const val EXTRA_TAB = "tab"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setTitle(R.string.tab_magnet)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        val keyword = intent?.getStringExtra(EXTRA_KEYWORD)
        val tab = intent?.getIntExtra(EXTRA_TAB, 0) ?: 0

        viewPager.adapter = object : FragmentStateAdapter(supportFragmentManager, lifecycle) {
            override fun createFragment(position: Int): Fragment {
                val f = MagnetFragment()
                if (!keyword.isNullOrBlank()) {
                    f.arguments = Bundle().apply { putString(EXTRA_KEYWORD, keyword) }
                }
                return f
            }
            override fun getItemCount() = 1
        }
        viewPager.setOffscreenPageLimit(2)
        bottomNav.setOnItemSelectedListener { item ->
            viewPager.currentItem = if (item.itemId == R.id.nav_music) 1 else 0
            true
        }
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bottomNav.menu.getItem(position).isChecked = true
                supportActionBar?.setTitle(if (position == 0) R.string.tab_magnet else R.string.tab_music)
            }
        })
        if (tab == 1) viewPager.setCurrentItem(1, false)
    }
}
