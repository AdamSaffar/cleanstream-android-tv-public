package com.cleanstream.engine;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lean-back catalog launcher. The visible library is generated from filters
 * that have actually been produced, so each card corresponds to a playable
 * CleanStream title.
 */
public class LauncherActivity extends Activity {

    private static final String NETFLIX_PKG = "com.netflix.ninja";
    private static final String NETFLIX_ACT = "com.netflix.ninja.MainActivity";
    private static final int NETFLIX_FLAGS = 0x10808000;

    private static final int BACKGROUND = 0xFF070809;
    private static final int SURFACE = 0xFF121519;
    private static final int SURFACE_FOCUSED = 0xFF1E2930;
    private static final int TEXT_PRIMARY = 0xFFF5F7FA;
    private static final int TEXT_SECONDARY = 0xFF9AA4AE;
    private static final int ACCENT = 0xFF73DFC5;
    private final List<CatalogRepository.Genre> catalog = new ArrayList<CatalogRepository.Genre>();
    private final List<CatalogRepository.Title> allTitles = new ArrayList<CatalogRepository.Title>();
    private final List<PosterRail> posterRails = new ArrayList<PosterRail>();

    /** One poster card plus the state needed for safe, on-demand image binding. */
    private static final class PosterBinding {
        final View card;
        final ImageView art;
        final CatalogRepository.Title item;
        boolean wanted;
        boolean loading;

        PosterBinding(View card, ImageView art, CatalogRepository.Title item) {
            this.card = card;
            this.art = art;
            this.item = item;
        }
    }

    /** Owns the bindings for one horizontal genre row. */
    private static final class PosterRail {
        final HorizontalScrollView view;
        final List<PosterBinding> posters = new ArrayList<PosterBinding>();

        PosterRail(HorizontalScrollView view) { this.view = view; }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { startService(new Intent(this, EngineService.class)); } catch (Exception ignored) { }
        catalog.addAll(CatalogRepository.load(this));
        for (CatalogRepository.Genre genre : catalog) allTitles.addAll(genre.titles);
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = dp(42);
        root.setPadding(side, dp(28), side, dp(52));

        root.addView(buildHeader());
        if (catalog.isEmpty()) {
            root.addView(buildEmptyState());
        } else {
            for (CatalogRepository.Genre genre : catalog) root.addView(buildGenreRow(genre));
            TextView attribution = text("Posters provided by TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.", 11, 0xFF6D7780);
            attribution.setPadding(0, dp(25), 0, 0);
            root.addView(attribution);
        }
        scroll.addView(root);
        scroll.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
            @Override public void onScrollChanged() { prefetchVisibleRails(); }
        });
        scroll.post(new Runnable() {
            @Override public void run() { prefetchVisibleRails(); }
        });
        return scroll;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(26));

        LinearLayout brandColumn = new LinearLayout(this);
        brandColumn.setOrientation(LinearLayout.VERTICAL);
        brandColumn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView brand = text("CLEANSTREAM", 30, TEXT_PRIMARY);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setLetterSpacing(0.08f);
        brandColumn.addView(brand);

        TextView subtitle = text(allTitles.size() + " filtered titles  •  Watch with confidence", 14, TEXT_SECONDARY);
        subtitle.setPadding(0, dp(5), 0, 0);
        brandColumn.addView(subtitle);
        header.addView(brandColumn);

        ImageButton search = new ImageButton(this);
        search.setImageResource(android.R.drawable.ic_menu_search);
        search.setColorFilter(TEXT_PRIMARY);
        search.setContentDescription("Search titles");
        search.setFocusable(true);
        search.setBackground(round(SURFACE, 24, 1, 0xFF273039));
        search.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(dp(52), dp(52));
        search.setLayoutParams(searchLp);
        search.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean hasFocus) {
                view.setBackground(round(hasFocus ? 0xFF254640 : SURFACE, 24,
                        hasFocus ? 2 : 1, hasFocus ? ACCENT : 0xFF273039));
                view.setScaleX(hasFocus ? 1.07f : 1f);
                view.setScaleY(hasFocus ? 1.07f : 1f);
            }
        });
        search.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showSearch(); }
        });
        header.addView(search);
        return header;
    }

    private View buildEmptyState() {
        TextView empty = text("Your filtered library is not packaged yet.\nRun tools/build_catalog.py after generating filters, then rebuild CleanStream.", 18, TEXT_SECONDARY);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), dp(72), dp(24), dp(72));
        empty.setBackground(round(SURFACE, 18, 1, 0xFF293039));
        return empty;
    }

    private View buildGenreRow(CatalogRepository.Genre genre) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.bottomMargin = dp(24);
        section.setLayoutParams(sectionLp);

        TextView heading = text(genre.name.toUpperCase(Locale.US) + "  " + genre.titles.size(), 16, TEXT_PRIMARY);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setLetterSpacing(0.04f);
        heading.setPadding(0, 0, 0, dp(11));
        section.addView(heading);

        HorizontalScrollView rail = new HorizontalScrollView(this);
        rail.setHorizontalScrollBarEnabled(false);
        rail.setClipToPadding(false);
        rail.setPadding(0, 0, dp(28), dp(10));
        final PosterRail posterRail = new PosterRail(rail);
        posterRails.add(posterRail);
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        for (CatalogRepository.Title item : genre.titles) {
            cards.addView(buildPosterCard(item, rail, posterRail));
        }
        rail.addView(cards);
        rail.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
            @Override public void onScrollChanged() { prefetchRail(posterRail); }
        });
        rail.post(new Runnable() {
            @Override public void run() { prefetchRail(posterRail); }
        });
        section.addView(rail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(244)));
        return section;
    }

    private View buildPosterCard(final CatalogRepository.Title item, final HorizontalScrollView rail,
                                 final PosterRail posterRail) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(5), dp(5), dp(5), dp(4));
        card.setBackground(round(SURFACE, 12, 1, 0xFF202830));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.MATCH_PARENT);
        cardLp.rightMargin = dp(12);
        card.setLayoutParams(cardLp);

        FrameLayout artFrame = new FrameLayout(this);
        artFrame.setBackground(round(0xFF222B33, 9, 0, 0));
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(172));
        artFrame.setLayoutParams(artLp);

        final ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(0xFF1C252C);
        artFrame.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView play = text("▶", 14, 0xFF0A0C0E);
        play.setGravity(Gravity.CENTER);
        play.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        play.setBackground(round(0xFFF3F7F7, 18, 0, 0));
        FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM | Gravity.LEFT);
        playLp.leftMargin = dp(8);
        playLp.bottomMargin = dp(8);
        artFrame.addView(play, playLp);
        card.addView(artFrame);

        TextView title = text(item.name, 13, TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(dp(5), dp(8), dp(5), 0);
        card.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(39)));

        TextView detail = text(item.detailLine(), 10, TEXT_SECONDARY);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        detail.setGravity(Gravity.CENTER_HORIZONTAL);
        detail.setPadding(dp(5), 0, dp(5), 0);
        card.addView(detail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));

        final PosterBinding poster = new PosterBinding(card, art, item);
        posterRail.posters.add(poster);

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(final View view, boolean hasFocus) {
                view.setBackground(round(hasFocus ? SURFACE_FOCUSED : SURFACE, 12,
                        hasFocus ? 2 : 1, hasFocus ? ACCENT : 0xFF202830));
                view.setScaleX(hasFocus ? 1.055f : 1f);
                view.setScaleY(hasFocus ? 1.055f : 1f);
                view.setElevation(hasFocus ? dp(9) : 0);
                if (hasFocus) {
                    poster.wanted = true;
                    loadPoster(poster);
                    rail.post(new Runnable() {
                        @Override public void run() {
                            rail.smoothScrollTo(Math.max(0, view.getLeft() - dp(30)), 0);
                        }
                    });
                }
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { launchTitle(item); }
        });
        return card;
    }

    private void prefetchVisibleRails() {
        Rect visible = new Rect();
        for (PosterRail rail : posterRails) {
            if (rail.view.getGlobalVisibleRect(visible) && visible.height() > dp(20)) {
                prefetchRail(rail);
            } else {
                releaseRail(rail);
            }
        }
    }

    private void releaseRail(PosterRail rail) {
        for (PosterBinding poster : rail.posters) {
            poster.wanted = false;
            if (!poster.loading) poster.art.setImageDrawable(null);
        }
    }

    private void prefetchRail(PosterRail rail) {
        int width = rail.view.getWidth();
        if (width <= 0) return;
        // Keep the current viewport plus one viewport ahead and behind warm.
        int leftEdge = rail.view.getScrollX() - width;
        int rightEdge = rail.view.getScrollX() + (2 * width);
        for (PosterBinding poster : rail.posters) {
            int left = poster.card.getLeft();
            int right = poster.card.getRight();
            poster.wanted = right >= leftEdge && left <= rightEdge;
            if (poster.wanted) {
                loadPoster(poster);
            } else if (!poster.loading) {
                // The bitmap remains eligible for the shared LRU cache, but a distant
                // ImageView must not pin it in memory indefinitely.
                poster.art.setImageDrawable(null);
            }
        }
    }

    private void loadPoster(final PosterBinding poster) {
        if (poster.loading || !poster.wanted || poster.item.posterUrl.isEmpty()
                || poster.art.getDrawable() != null) return;
        poster.loading = true;
        PosterFetcher.fetchByUrl(poster.item.posterUrl, new PosterFetcher.Callback() {
            @Override public void onPoster(Bitmap bitmap) {
                poster.loading = false;
                if (bitmap != null && poster.wanted) poster.art.setImageBitmap(bitmap);
            }
        });
    }

    private void showSearch() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(26), dp(28), dp(26));
        content.setBackground(round(0xFF101419, 22, 1, 0xFF34414A));

        TextView heading = text("SEARCH CLEANSTREAM", 18, TEXT_PRIMARY);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setLetterSpacing(0.05f);
        content.addView(heading);

        final EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setTextColor(TEXT_PRIMARY);
        query.setHintTextColor(0xFF7E8994);
        query.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        query.setHint("Search titles or genres");
        query.setPadding(dp(16), 0, dp(16), 0);
        query.setBackground(round(0xFF1B2228, 12, 1, 0xFF36424C));
        LinearLayout.LayoutParams queryLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        queryLp.topMargin = dp(16);
        content.addView(query, queryLp);

        TextView count = text("Type to search your filtered library", 13, TEXT_SECONDARY);
        count.setPadding(0, dp(15), 0, dp(9));
        content.addView(count);

        ScrollView resultScroll = new ScrollView(this);
        resultScroll.setVerticalScrollBarEnabled(false);
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        resultScroll.addView(results);
        content.addView(resultScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(390)));

        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int countChanged) {
                populateSearchResults(results, count, s.toString(), dialog);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        populateSearchResults(results, count, "", dialog);

        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT);
        query.requestFocus();
        query.postDelayed(new Runnable() {
            @Override public void run() {
                InputMethodManager input = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (input != null) input.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 180);
    }

    private void populateSearchResults(LinearLayout results, TextView count, String rawQuery, final Dialog dialog) {
        results.removeAllViews();
        String query = rawQuery.trim().toLowerCase(Locale.US);
        int found = 0;
        for (final CatalogRepository.Title item : allTitles) {
            if (!query.isEmpty() && !item.name.toLowerCase(Locale.US).contains(query)
                    && !item.genre.toLowerCase(Locale.US).contains(query)
                    && !item.episode.toLowerCase(Locale.US).contains(query)) continue;
            if (found++ >= 80) break;
            LinearLayout result = new LinearLayout(this);
            result.setOrientation(LinearLayout.VERTICAL);
            result.setFocusable(true);
            result.setClickable(true);
            result.setPadding(dp(16), dp(11), dp(16), dp(11));
            result.setBackground(round(SURFACE, 10, 1, 0xFF26313A));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            result.setLayoutParams(lp);
            TextView name = text(item.name, 16, TEXT_PRIMARY);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            result.addView(name);
            TextView meta = text(item.genre + (item.detailLine().isEmpty() ? "" : "  •  " + item.detailLine()), 12, TEXT_SECONDARY);
            meta.setPadding(0, dp(3), 0, 0);
            result.addView(meta);
            result.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View view, boolean hasFocus) {
                    view.setBackground(round(hasFocus ? SURFACE_FOCUSED : SURFACE, 10,
                            hasFocus ? 2 : 1, hasFocus ? ACCENT : 0xFF26313A));
                }
            });
            result.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    dialog.dismiss();
                    launchTitle(item);
                }
            });
            results.addView(result);
        }
        if (query.isEmpty()) count.setText("Showing all " + allTitles.size() + " filtered titles");
        else if (found == 80) count.setText("Showing the first 80 matching titles");
        else count.setText(found + (found == 1 ? " matching title" : " matching titles"));
        if (found == 0) {
            TextView empty = text("No filtered title matches \"" + rawQuery.trim() + "\".", 15, TEXT_SECONDARY);
            empty.setPadding(dp(8), dp(24), dp(8), dp(24));
            results.addView(empty);
        }
    }

    private void launchTitle(final CatalogRepository.Title item) {
        File filter = new File(item.filterPath());
        if (!filter.exists()) {
            toast("Filter missing: filter_" + item.netflixId + ".json — push it to the TV first.");
            return;
        }
        EngineService service = EngineService.get();
        if (service == null) {
            startService(new Intent(this, EngineService.class));
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { launchTitle(item); }
            }, 700);
            return;
        }
        String result = service.armTitle(item.filterPath());
        if (result == null || !result.startsWith("OK")) {
            toast("Engine arm failed: " + result);
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { fireNetflixDeepLink(item.netflixId); }
        }, 400);
    }

    private void fireNetflixDeepLink(String netflixId) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.netflix.com/title/" + netflixId));
            intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            intent.setComponent(new ComponentName(NETFLIX_PKG, NETFLIX_ACT));
            intent.setFlags(NETFLIX_FLAGS);
            intent.putExtra("source", "30");
            startActivity(intent);
        } catch (Exception exception) {
            toast("Could not launch Netflix: " + exception.getMessage());
        }
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        result.setTextColor(color);
        return result;
    }

    private GradientDrawable round(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) result.setStroke(dp(strokeDp), strokeColor);
        return result;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
