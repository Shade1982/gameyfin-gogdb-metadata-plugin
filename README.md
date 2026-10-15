# Gameyfin GOGDB Metadata Plugin

A metadata provider plugin for [Gameyfin](https://github.com/gameyfin/gameyfin)
that fetches game metadata from [GOG DB](https://www.gogdb.org/).

## Features

- Searches GOG's public catalog by title to find product IDs, following its
  `searchAfter` cursor until enough matches are found or the search is
  exhausted.
- Fetches product metadata from GOG DB's published JSON data files. It does
  not scrape the GOG DB website or download its full database.
- Provides descriptions, covers, backgrounds, screenshots, video links,
  release dates, developers, publishers, genres, themes, features, keywords,
  and supported platforms when available.
- Caches fetched metadata in memory with a 256-entry least-recently-used limit
  and a two-hour expiry.

GOG DB documents its machine-readable data at
[gogdb.org/moreinfo](https://www.gogdb.org/moreinfo). Catalog search is used
only to identify matching GOG product IDs; metadata comes from GOG DB.
HTTP behavior is tested with a mock client, so the test suite does not depend
on either service being online.

## Build

Requires JDK 21 or newer.

```sh
./gradlew build
```

Run tests with:

```sh
./gradlew test
```

## Installation

1. Download the latest `gogdb-plugin-x.y.z.jar` from the Releases page.
2. Place the JAR in the `plugins/` directory of your Gameyfin installation.
   Create that directory if it does not exist.
3. Restart Gameyfin.

### Docker Compose

Mount the plugin JAR into Gameyfin's plugin directory:

```yaml
services:
  gameyfin:
    volumes:
      - /your/location/gogdb-plugin-x.y.z.jar:/opt/gameyfin/plugins/gogdb-plugin-x.y.z.jar
```
