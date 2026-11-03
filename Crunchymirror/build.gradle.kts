// use an integer for version numbers
version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}

cloudstream {

    language = "en"
    // All of these properties are optional, you can safely remove them

    description = "Crunchyroll Catalog & Streams Provider for Cloudstream (Anime/AnimeMovie with Dub/Sub support)"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Anime",
        "AnimeMovie"
    )

    iconUrl = "https://raw.githubusercontent.com/<ronnyadriel>/Crunchymirror-Extension/builds/logo.png"
}