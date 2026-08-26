## What's Different From Mihon

This is a personal fork of Mihon with a few reader-focused additions on top of the original app:

<div align="left">

* **AI-powered image upscaling** — enhances low-resolution images in real time using Real-CUGAN, Real-CUGAN Pro, Real-ESRGAN, waifu2x, Anime4K, AnimeJaNai, and community super-resolution models (NomosUni-SPAN, sudo-UltraCompact), running on [ncnn](https://github.com/Tencent/ncnn). Supports Vulkan, CPU, and Qualcomm NPU (QNN) processing backends, per-series overrides (model/noise/scale/style, independent of whether upscaling is enabled at all for that series), and a local disk cache so a page is only enhanced once.

* **Guided View** — an ML-assisted panel-by-panel reading mode that automatically detects panel boundaries and reading order on each page, with a manual panel editor for correcting individual pages, per-series/app-wide reading direction and swipe-direction controls, and optional full-page intro/outro stops per chapter.

</div>

## Fork Acknowledgements

<div align="left">

* The AI upscaling integration is based on [HaoweiLi97/mihon_img_upscale](https://github.com/HaoweiLi97/mihon_img_upscale), which itself credits [Bilibili AI Lab](https://github.com/bilibili/ailab) (Real-CUGAN), [Xintao Wang et al.](https://github.com/xinntao/Real-ESRGAN) (Real-ESRGAN), and [nagadomi](https://github.com/nagadomi/waifu2x) (waifu2x).
* Guided View's panel detection model is [leoxs22/manga-panel-detector-yolo26n](https://huggingface.co/leoxs22/manga-panel-detector-yolo26n), a YOLO26-nano model trained on the [Manga109-s](http://www.manga109.org/) dataset, © the Manga109 project (Aizawa et al., University of Tokyo) — see [Building a Manga Dataset "Manga109" with Annotations for Multimedia Applications](https://doi.org/10.1109/mmul.2020.2987895) (2020) and [Sketch-based Manga Retrieval using Manga109 Dataset](https://doi.org/10.1007/s11042-016-4020-z) (2017).

</div>

> **Performance note:** all testing for the AI upscaling and Guided View features has been done on a Samsung Galaxy S24 Ultra. Performance on lower-powered devices is untested and unknown.

<br>

<div align="center">

<a href="https://mihon.app">
    <img src="./.github/assets/logo.png" alt="Mihon logo" title="Mihon logo" width="80"/>
</a>

# Mihon [App](#)

### Full-featured reader
Discover and read manga, webtoons, comics, and more – easier than ever on your Android device.

[![Discord server](https://img.shields.io/discord/1195734228319617024.svg?label=&labelColor=6A7EC2&color=7389D8&logo=discord&logoColor=FFFFFF)](https://discord.gg/mihon)
[![GitHub downloads](https://img.shields.io/github/downloads/mihonapp/mihon/total?label=downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://mihon.app/download)

[![CI](https://img.shields.io/github/actions/workflow/status/mihonapp/mihon/build.yml?labelColor=27303D)](https://github.com/mihonapp/mihon/actions/workflows/build_push.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/mihonapp/mihon?labelColor=27303D&color=0877d2)](/LICENSE)
[![Translation status](https://img.shields.io/weblate/progress/mihon?labelColor=27303D&color=946300)](https://hosted.weblate.org/engage/mihon/)

## Download

[![Mihon Stable](https://img.shields.io/github/release/mihonapp/mihon.svg?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://mihon.app/download)
[![Mihon Beta](https://img.shields.io/github/v/release/mihonapp/mihon-preview.svg?maxAge=3600&label=Beta&labelColor=2c2c47&color=1c1c39)](https://mihon.app/download)

*Requires Android 8.0 or higher.*

## Features

<div align="left">

* Local reading of content.
* A configurable reader with multiple viewers, reading directions and other settings.
* Tracker support: [MangaBaka](https://mangabaka.org), [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://mangaupdates.com), [Shikimori](https://shikimori.one), [Bangumi](https://bgm.tv/), and [Hikka](https://hikka.io/) support.
* Categories to organize your library.
* Light and dark themes.
* Schedule updating your library for new chapters.
* Create backups locally to read offline or to your desired cloud service.
* Plus much more...

</div>

## Contributing

[Code of conduct](./CODE_OF_CONDUCT.md) · [Contributing guide](./CONTRIBUTING.md)

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

Before reporting a new issue, take a look at the [FAQ](https://mihon.app/docs/faq/general), the [changelog](https://mihon.app/changelogs/) and the already opened [issues](https://github.com/mihonapp/mihon/issues); if you got any questions, join our [Discord server](https://discord.gg/mihon).


### Repositories

[![mihonapp/website - GitHub](https://github-stats-extended.vercel.app/api/pin/?username=mihonapp&repo=website&bg_color=161B22&text_color=c9d1d9&title_color=0877d2&icon_color=0877d2&border_radius=8&hide_border=true&description_lines_count=2)](https://github.com/mihonapp/website/)
[![mihonapp/bitmap.kt - GitHub](https://github-stats-extended.vercel.app/api/pin/?username=mihonapp&repo=bitmap.kt&bg_color=161B22&text_color=c9d1d9&title_color=0877d2&icon_color=0877d2&border_radius=8&hide_border=true&description_lines_count=2)](https://github.com/mihonapp/bitmap.kt/)

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/mihonapp/mihon/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=mihonapp/mihon" alt="Mihon app contributors" title="Mihon app contributors" width="800"/>
</a>

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>

</div>
