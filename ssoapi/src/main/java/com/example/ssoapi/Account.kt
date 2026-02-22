package com.example.ssoapi

import android.os.Parcel
import android.os.Parcelable

/**
 * Account data class that represents a user account.
 * This class is Parcelable so it can be passed via AIDL.
 *
 * Fields:
 * - guid: Unique identifier for the account
 * - mail: Email address
 * - profileImage: Optional profile image URL
 * - sessionToken: Authentication session token
 * - isActive: Whether this is the currently active account
 */
data class Account(
    val guid: String = "",
    val mail: String = "",
    val profileImage: String? = null,
    val sessionToken: String = "",
    val isActive: Boolean = false
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString(),
        parcel.readString() ?: "",
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(guid)
        parcel.writeString(mail)
        parcel.writeString(profileImage)
        parcel.writeString(sessionToken)
        parcel.writeByte(if (isActive) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Account> {
        override fun createFromParcel(parcel: Parcel): Account {
            return Account(parcel)
        }

        override fun newArray(size: Int): Array<Account?> {
            return arrayOfNulls(size)
        }
    }
}
