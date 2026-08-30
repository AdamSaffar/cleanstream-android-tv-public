package com.cleanstream.engine;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Reads the generated, packaged catalog. Filter files themselves stay on-device. */
final class CatalogRepository {

    static final class Genre {
        final String name;
        final List<Title> titles = new ArrayList<Title>();
        Genre(String name) { this.name = name; }
    }

    static final class Title {
        final String netflixId;
        final String name;
        final String episode;
        final String genre;
        final String certification;
        final String posterUrl;
        final String overview;
        final String releaseDate;

        Title(String netflixId, String name, String episode, String genre,
              String certification, String posterUrl, String overview, String releaseDate) {
            this.netflixId = netflixId;
            this.name = name;
            this.episode = episode;
            this.genre = genre;
            this.certification = certification;
            this.posterUrl = posterUrl;
            this.overview = overview;
            this.releaseDate = releaseDate;
        }

        String filterPath() {
            return "/sdcard/Android/data/com.cleanstream.engine/files/filter_"
                    + netflixId + ".json";
        }

        String detailLine() {
            StringBuilder result = new StringBuilder();
            if (!episode.isEmpty()) result.append(episode);
            if (!certification.isEmpty()) {
                if (result.length() > 0) result.append("  •  ");
                result.append(certification);
            }
            if (!releaseDate.isEmpty()) {
                if (result.length() > 0) result.append("  •  ");
                result.append(releaseDate.length() >= 4 ? releaseDate.substring(0, 4) : releaseDate);
            }
            return result.toString();
        }
    }

    private CatalogRepository() { }

    static List<Genre> load(Context context) {
        ArrayList<Genre> result = new ArrayList<Genre>();
        InputStream in = null;
        try {
            in = context.getAssets().open("catalog.json");
            JSONObject root = new JSONObject(readAll(in));
            JSONArray groups = root.optJSONArray("genres");
            if (groups == null) return result;
            for (int i = 0; i < groups.length(); i++) {
                JSONObject groupJson = groups.optJSONObject(i);
                if (groupJson == null) continue;
                Genre group = new Genre(groupJson.optString("name", "Other"));
                JSONArray titles = groupJson.optJSONArray("titles");
                if (titles != null) {
                    for (int j = 0; j < titles.length(); j++) {
                        JSONObject item = titles.optJSONObject(j);
                        if (item == null) continue;
                        String id = item.optString("netflix_id", "");
                        String name = item.optString("title", "");
                        if (id.isEmpty() || name.isEmpty()) continue;
                        group.titles.add(new Title(id, name, item.optString("episode", ""),
                                item.optString("genre", group.name), item.optString("certification", ""),
                                item.optString("poster_url", ""), item.optString("overview", ""),
                                item.optString("release_date", "")));
                    }
                }
                if (!group.titles.isEmpty()) result.add(group);
            }
        } catch (Exception ignored) {
            // The launcher renders a clear empty-library message if the asset is absent/bad.
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
        return result;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toString("UTF-8");
    }
}
