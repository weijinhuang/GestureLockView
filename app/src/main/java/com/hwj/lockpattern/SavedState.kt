package com.hwj.lockpattern

import android.os.Parcel
import android.os.Parcelable
import android.view.View

/**
 * Created by HuangWeiJin on 2018/07/16.
 */
class SavedState: View.BaseSavedState {

    lateinit var mSerializedPattern: String
    var mDisplayMode = 0
    var mInputEnable = false
    var mInStealthMode = false
    var mTactileFeedbackEnabled = false


    private constructor(source: Parcel) : super(source) {
        mSerializedPattern = source.readString()
        mDisplayMode = source.readInt()
        mInputEnable = source.readValue(null) as Boolean
        mInStealthMode = source.readValue(null) as Boolean
        mTactileFeedbackEnabled = source.readValue(null) as Boolean
    }

    private constructor(superState: Parcelable?) : super(superState)

    constructor(superState: Parcelable, serializedPattern: String, displayMode: Int, inputEnable: Boolean, inStealthMode: Boolean, tactileFeedbackEnable: Boolean) : this(superState) {
        mSerializedPattern = serializedPattern
        mDisplayMode = displayMode
        mInputEnable = inputEnable
        mInStealthMode = inStealthMode
        mTactileFeedbackEnabled = tactileFeedbackEnable
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        super.writeToParcel(dest, flags)
        dest.writeString(mSerializedPattern)
        dest.writeInt(mDisplayMode)
        dest.writeValue(mInputEnable)
        dest.writeValue(mInStealthMode)
        dest.writeValue(mTactileFeedbackEnabled)
    }

    companion object {

        val CREATOR = object : Parcelable.Creator<SavedState> {
            override fun createFromParcel(source: Parcel): SavedState {
                return SavedState(source)
            }

            override fun newArray(size: Int): Array<SavedState?> {
                return Array(size) { null }
            }

        }
    }

}