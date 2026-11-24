package com.aifindres.lite.data

import com.google.gson.annotations.SerializedName

class SiteExpr {
    @SerializedName("group") var group: String? = null
    @SerializedName("title") var title: String? = null
    @SerializedName("date") var date: String? = null
    @SerializedName("size") var size: String? = null
    @SerializedName("url") var url: String? = null
    @SerializedName("magnet") var magnet: String? = null
}

class SiteRequest {
    @SerializedName("method") var method: String? = null
    @SerializedName("mainUrl") var mainUrl: String? = null
    @SerializedName("searchUrl") var searchUrl: String? = null
    @SerializedName("data") var data: String? = null
    @SerializedName("header") var header: Map<String, String>? = null
}

class Site {
    @SerializedName("id") var id: Int = 0
    @SerializedName("name") var name: String = ""
    @SerializedName("info") var info: String = ""
    @SerializedName("state") var state: Boolean = false
    @SerializedName("time") var time: String? = null
    @SerializedName("updateTime") var updateTime: String? = null
    @SerializedName("request") var request: SiteRequest? = null
    @SerializedName("expressionModel") var expressionModel: SiteExpr? = null
}
