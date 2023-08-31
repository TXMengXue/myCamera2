/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2basic;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

public class CameraActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设置布局文件
        setContentView(R.layout.activity_camera);
        // 如果没有保存的状态，就创建一个新的Fragment Fragment是一个可重用的组件，用于构建灵活的用户界面
        if (null == savedInstanceState) {
            // 创建一个新的Fragment
            getSupportFragmentManager().beginTransaction()  // 开启一个事务
                    .replace(R.id.container, Camera2BasicFragment.newInstance())    // 替换Fragment
                    .commit();  // 提交事务
        }
    }

}
