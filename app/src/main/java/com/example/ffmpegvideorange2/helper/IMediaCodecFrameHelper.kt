package com.example.ffmpegvideorange2.helper

import android.graphics.*
import android.media.*
import android.os.Build
import android.os.Environment
import android.util.Log
import android.view.Surface
import android.widget.ImageView
import com.example.ffmpegvideorange2.ImageViewBitmapBean
import com.example.ffmpegvideorange2.ShowFrameQueue
import com.example.ffmpegvideorange2.Utils
import com.example.ffmpegvideorange2.VideoUtils
import com.example.myplayer.KzgPlayer
import com.example.myplayer.PacketQueue
import com.example.myplayer.mediacodec.KzglVideoSupportUtil
import com.example.myplayer.mediacodecframes.VideoToFrames
import com.sam.video.timeline.bean.TargetBean
import com.sam.video.timeline.helper.IAvFrameHelper
import com.sam.video.timeline.helper.IFrameSearch
import com.sam.video.timeline.helper.OnGetFrameBitmapCallback
import com.sam.video.util.notNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*


class IMediaCodecFrameHelper(
    override var filePath:String = "",
    override var onGetFrameBitmapCallback: OnGetFrameBitmapCallback?
) : IAvFrameHelper,Runnable {

    private var kzgPlayer:KzgPlayer? = null
    private var mediaFormat:MediaFormat? = null
    private var mediaCodec:MediaCodec? = null
    private var videoDecodeInfo: MediaCodec.BufferInfo? = null
    //存放从ffmpeg传过来的AVPacket数据
    val packetQueue:PacketQueue = PacketQueue()
    //存放imageview 和要转为bitmap的Image
    val imageQueue:ShowFrameQueue = ShowFrameQueue()

    private var lastIDRIndex: Int = 0
    var targetViewMap:Hashtable<ImageView, TargetBean> = Hashtable()
    private var childThread:Thread? = null
    private var imageToBitmaptThread:Thread? = null
    //是否停止解码线程
    private var isStop = false
    //当前最后解码出来的一个帧，用来作为还没有来得及解码的预览帧
    override var lastBitMap: Bitmap? = null
    override var isSeekBack: Boolean = true
    override var isScrolling: Boolean = false
    override var decodeFrameListener: IAvFrameHelper.DecodeFrameListener? = null
    //是初始化了recyclerView的Item
    private var isInitItem = false
    private var lastCodecFramePts = 0L
    private var mapEntry:Map.Entry<ImageView,TargetBean>? = null
    private var startTime = 0L
    private var firstTimesFrame = 0;
    private var timeOut = 10L
    //是否是用队列的方式去异步转bitmap
    private var isUseQueue = true


    override fun init() {
        childThread = Thread(this)
        childThread!!.start()

        if (isUseQueue){
            imageToBitmaptThread = Thread(imageToBitmapRunnable)
            imageToBitmaptThread!!.start()
        }
    }

    override fun loadAvFrame(view: ImageView, timeMs: Long) {
        targetViewMap[view] = targetViewMap[view]?:TargetBean()
        Log.e("kzg","**************seekTime0:${timeMs} , $view , ${view.tag}")
        lastBitMap?.let {
            if (targetViewMap[view]?.isAddFrame == false){
                view.setImageBitmap(it)
            }
        }
        if (targetViewMap[view]?.timeUs != timeMs){
            targetViewMap[view]?.timeUs = timeMs
            targetViewMap[view]?.isAddFrame = false
        }


        if (!isInitItem) {
            isInitItem = true
            kzgPlayer?.pauseGetPacket(false)
        }


    }

    override fun release() {
        Log.e("kzg","*********************开始销毁IMediaCodecFrameHelper")
        isStop = true

        childThread?.join()
        imageToBitmaptThread?.join()

        kzgPlayer?.let {
            it.release()
            kzgPlayer = null
        }

        mediaFormat = null
        mediaCodec?.let {
            it.release()
            mediaCodec = null
        }

        targetViewMap.clear()

        packetQueue.clear()
        imageQueue.clear()
        Log.e("kzg","*********************销毁IMediaCodecFrameHelper")
    }

    override fun seek() {
        Log.e("kzg","****************开始seek")
        startTime = System.currentTimeMillis()
        Utils.sortHashMap(targetViewMap).apply {
            var i=0
            var j=0
            var minTimeUs = Long.MAX_VALUE
            var hasNoAddFrame = false
            this.forEach {
                if (!it.value.isAddFrame){
                    minTimeUs = if (minTimeUs < it.value.timeUs) minTimeUs else it.value.timeUs
                    hasNoAddFrame = true
                }
            }
            //如果没有需要解码的帧，就直接返回
            if (!hasNoAddFrame){
                return
            }
            val func =  {
                Log.e("kzg","********************isCurrentGop11111111:${minTimeUs}")
                val ite = IFrameSearch.IframeUs.iterator()
                var index = 0
                while (ite.hasNext()){
                    val frame = ite.next()
                    //当前recyclerView最小的item帧的时间戳所属的gop index
                    if (index > 0 && minTimeUs >=IFrameSearch.IframeUs[index - 1] && minTimeUs < frame){
                        i = index
                    }

                    //已解码的帧的pts所属的gop
                    if (index > 0 && lastCodecFramePts >=IFrameSearch.IframeUs[index - 1] && lastCodecFramePts < frame ){
                        j = index
                    }
                    index ++
                }

                if (isSeekBack){
                    false
                }else{
                    //如果将要解码的帧所属的gop与已经解码出来的最后一帧所属的是同一个gop 或者 将要解码的帧的时间小于 avpacket队列的最大帧的时间，就认为是同一个gop
                    i == j || minTimeUs <= packetQueue.maxPts.toLong()
                }
            }

            val isCurrentGop =func().apply {
                Log.e("kzg","********************isCurrentGop:$this")
                if (!this){
                    packetQueue.clear()
                    mediaCodec?.flush()
                }
            }
            //如果还在一个gop内，就取需要显示的帧的时间（这种情况其实不需要用到这个），如果不在同一个gop,就取要显示的的帧的pts所在的gop
            val pts = (if (isCurrentGop) minTimeUs/1000_000.0 else IFrameSearch.IframeUs[i-1]/1000_000.0).apply {
                Log.e("kzg","********************pts:$this")
            }
            kzgPlayer?.seekFrame(pts.toDouble(),isCurrentGop)
        }
    }


    override fun pause() {
        Log.e("kzg","**************pause:")
        kzgPlayer?.pauseGetPacket(true)
    }

    override fun run() {
        while (!isStop){
            if (packetQueue.queueSize  == 0 || isScrolling){
                Thread.sleep(10)
                continue
            }
            //按照时间从小到大排序
            //targetViewMap = targetViewMap.entries.sortedBy { it.value.timeUs }.associateBy ({it.key},{it.value}) as MutableMap<ImageView, TargetBean>

            run task@{
                Utils.sortHashMap(targetViewMap).forEach {
                    packetQueue.first?.let {bean ->
                        //Log.e("kzg","**************isAddFrame:${it.value.isAddFrame}")
                        if ((it.value.timeUs.toDouble() >= bean.pts && !it.value.isAddFrame) || !it.value.isAddFrame){
                            if (packetQueue.queueSize < 10 && !isScrolling){
                                kzgPlayer?.pauseGetPacket(false)
                            }
                            packetQueue.deQueue().apply {
                                //Log.e("kzg","***************timeUs:${it.value.timeUs.toDouble()}  , pts:${this.pts}  ,isAddFrame:${it.value.isAddFrame}")
                                if (this !=null && this.data != null && this.data.isNotEmpty()){
                                    mapEntry = it
                                    Log.e("kzg","**************mediacodecDecode")
                                    mediacodecDecode(this.data,this.dataSize,this.pts.toLong(),it)
                                }
                                return@task
                            }

                        }
                    }

                }

            }

        }
        Log.e("kzg","*******************结束预览条解码线程")
    }


    fun setKzgPlayer(player: KzgPlayer){
        this.kzgPlayer = player
    }

    fun initMediaCodec(codecName: String?,
                               width: Int,
                               height: Int,
                               csd_0: ByteArray?,
                               csd_1: ByteArray?,
                    surface: Surface
    ){

        val mime = KzglVideoSupportUtil.findVideoCodecName(codecName)
        //创建MediaFormat
        mediaFormat = MediaFormat.createVideoFormat(mime, width, height)
        Log.e("kzg","*******************初始化获取预览帧的解码器：$codecName ,  $width  ,  $height , $mime")
        //设置参数
        mediaFormat!!.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
        mediaFormat!!.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        mediaFormat!!.setByteBuffer("cds-0", ByteBuffer.wrap(csd_0))
        mediaFormat!!.setByteBuffer("cds-1", ByteBuffer.wrap(csd_1))
        Log.e("kzg", "**************mediaFormat:" + mediaFormat.toString())
        videoDecodeInfo = MediaCodec.BufferInfo()

        try {
            mediaCodec = MediaCodec.createDecoderByType(mime)

            mediaCodec!!.configure(mediaFormat, null, null, 0)
            mediaCodec!!.start()
            kzgPlayer?.startGetFrame()
            kzgPlayer?.getFrameListener?.onStarGetFrame()
            Log.e("kzg", "**************mediacodec 开始解码抽帧" )
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }


    private fun mediacodecDecode(bytes: ByteArray?, size: Int, pts: Long,mapEntry:Map.Entry<ImageView, TargetBean>) {
        Log.e("kzg","************************mediacodec 开始解码帧：$pts  ,timeUs:${mapEntry.value.timeUs}   ,耗时：${System.currentTimeMillis() - startTime}")
        if (bytes != null && mediaCodec != null && videoDecodeInfo != null) {
            try {
                var codecStartTime = System.currentTimeMillis()
                val inputBufferIndex = mediaCodec!!.dequeueInputBuffer(10)
                if (inputBufferIndex >= 0) {
                    //前几帧反正解码不出来，timeout 可以设置的小一些
                    firstTimesFrame ++
                    if (firstTimesFrame > 3 && timeOut < 10000){
                        timeOut = 10000
                    }
                    val inputBuffer: ByteBuffer? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        mediaCodec!!.getInputBuffer(inputBufferIndex)
                    } else {
                        mediaCodec!!.inputBuffers[inputBufferIndex]
                    }
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(bytes)
                        mediaCodec!!.queueInputBuffer(inputBufferIndex, 0, size, pts, 0)
                    }
                }else{
                    Log.e("kzg","**************mediacodec dequeueInputBuffer 失败")
                }

                var index = mediaCodec!!.dequeueOutputBuffer(videoDecodeInfo, timeOut)
                var buffer:ByteBuffer? = null
                while (index >= 0) {
                    if (isScrolling){
                        mediaCodec!!.releaseOutputBuffer(index, false)
                        index = mediaCodec!!.dequeueOutputBuffer(videoDecodeInfo, 10)
                        continue
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        Log.e("kzg","**********************mediacodec 解码出一帧:${videoDecodeInfo!!.presentationTimeUs}  ,耗时：${System.currentTimeMillis() - codecStartTime}")
                        codecStartTime = System.currentTimeMillis()
                        lastCodecFramePts = videoDecodeInfo!!.presentationTimeUs
                        // TODO 这里需要优化，将具体需要放宽的时间范围，根据帧率来计算，比如这里的40_000 和 60_000，需要根据实际帧率来算每帧间隔实际
                        if (((mapEntry.value.timeUs >= videoDecodeInfo!!.presentationTimeUs-20_000 && mapEntry.value.timeUs<=videoDecodeInfo!!.presentationTimeUs+20_000)
                            || (videoDecodeInfo!!.presentationTimeUs-mapEntry.value.timeUs>=30_000)  ||(mapEntry.value.timeUs < 30_000 && videoDecodeInfo!!.presentationTimeUs > mapEntry.value.timeUs))
                            && !mapEntry.value.isAddFrame){
                            val image = mediaCodec!!.getOutputImage(index)
                            val rect = image!!.cropRect
                            val yuvImage = YuvImage(
                                VideoUtils.YUV_420_888toNV21(image),
                                ImageFormat.NV21,
                                rect.width(),
                                rect.height(),
                                null
                            )

                            if (isUseQueue){
                                //使用队列异步转bitmap
                                imageQueue.enQueue(ImageViewBitmapBean(mapEntry,yuvImage,rect,0))
                                if (!isScrolling){
                                    this.mapEntry!!.value.isAddFrame = true
                                }
                                Log.e("kzg","**********************yuvImage  耗时：${System.currentTimeMillis() - startTime}")
                            }else{
                                val stream = ByteArrayOutputStream()
                                yuvImage.compressToJpeg(Rect(0, 0, rect.width(), rect.height()), 100, stream)
                                // 检查bitmap的大小
                                val options = BitmapFactory.Options()
                                // 设置为true，BitmapFactory会解析图片的原始宽高信息，并不会加载图片
                                options.inJustDecodeBounds = true
                                var bitmap = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size(),options)
                                //算出合适的缩放比例
                                options.inSampleSize = Utils.calculateInSampleSize(options,100,100)
                                // 设置为false，加载bitmap
                                options.inJustDecodeBounds = false
                                bitmap =
                                    BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size(),options)
                                lastBitMap = bitmap

                                this.mapEntry!!.key!!.post {
                                    bitmap?.let {
                                        this.mapEntry!!.key!!.setImageBitmap(it)
                                        targetViewMap.forEach { mp ->
                                            if (!mp.value.isAddFrame){
                                                mp.key.setImageBitmap(lastBitMap)
                                            }

                                        }
                                    }
                                }
                                //Utils.saveBitmap("${Environment.getExternalStorageDirectory()}/jpe/",String.format("frame_%05d.jpg", mapEntry.value.timeUs),bitmap)
                                if (!isScrolling){
                                    this.mapEntry!!.value.isAddFrame = true
                                }
                                //Log.e("kzg","**********************展示一帧 timeUs: ${this.mapEntry!!.value.timeUs} ,pts:${videoDecodeInfo!!.presentationTimeUs}  ,耗时：${System.currentTimeMillis() - startTime}")
                            }
                            image?.close()
                        }

                    }else{
                        buffer = mediaCodec!!.outputBuffers[index]
                    }
                    mediaCodec!!.releaseOutputBuffer(index, false)
                    buffer?.clear()
                    index = mediaCodec!!.dequeueOutputBuffer(videoDecodeInfo, timeOut)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }finally {

            }
        }
    }


    private val imageToBitmapRunnable = Runnable {
        while (!isStop){
            if (imageQueue.queueSize == 0){
                Thread.sleep(5)
                continue
            }

            imageQueue.deQueue().apply {
                notNull(this.image,this.mapEntry!!.key,this.rect){
                   /* val rect = this.image!!.cropRect
                    val yuvImage = YuvImage(
                        VideoUtils.YUV_420_888toNV21(image),
                        ImageFormat.NV21,
                        rect.width(),
                        rect.height(),
                        null
                    )*/
                    val stream = ByteArrayOutputStream()
                    this.image!!.compressToJpeg(Rect(0, 0, this.rect!!.width(), this.rect!!.height()), 100, stream)
                    // 检查bitmap的大小
                    val options = BitmapFactory.Options()
                    // 设置为true，BitmapFactory会解析图片的原始宽高信息，并不会加载图片
                    options.inJustDecodeBounds = true
                    var bitmap = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size(),options)
                    //算出合适的缩放比例
                    options.inSampleSize = Utils.calculateInSampleSize(options,60,60)
                    // 设置为false，加载bitmap
                    options.inJustDecodeBounds = false
                    bitmap =
                        BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size(),options)
                    lastBitMap = bitmap

                    this.mapEntry!!.key!!.post {
                        bitmap?.let {
                            this.mapEntry!!.key!!.setImageBitmap(it)
                            targetViewMap.forEach { mp ->
                                if (!mp.value.isAddFrame){
                                    mp.key.setImageBitmap(lastBitMap)
                                }

                            }
                        }
                    }
                    //Utils.saveBitmap("${Environment.getExternalStorageDirectory()}/jpe/",String.format("frame_%05d.jpg", mapEntry.value.timeUs),bitmap)
                    if (!isScrolling){
                        this.mapEntry!!.value.isAddFrame = true
                    }
                    //Log.e("kzg","**********************展示一帧 timeUs: ${this.mapEntry!!.value.timeUs} ,pts:${videoDecodeInfo!!.presentationTimeUs}  ,耗时：${System.currentTimeMillis() - this.time}")
                }
            }

        }
    }

}