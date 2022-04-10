package com.sam.video.timeline.helper

import android.widget.ImageView

/**
 * 视频抽帧接口
 */
interface IAvFrameHelper {
    var filePath:String
    var onGetFrameBitmapCallback:OnGetFrameBitmapCallback?

    fun init()
    /**
     * 获取指定时间的帧数据，并显示到指定的view中
     *
     */
    fun loadAvFrame(view:ImageView,timeMs:Long)

    fun release()

    fun seek()

    fun pause()

}