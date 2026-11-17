package io.github.dennisochulor.flashcards.config;

import io.github.dennisochulor.flashcards.questions.CardView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Help: how the mod works, in Minecraft's own tabs (Start, Cards, AI grading, Decks, Rewards, HUD, Problems).
 * Each page scrolls, and ends with buttons that open the matching settings (which come back here).
 */
public class HelpScreen extends Screen {
    private static final int PAGE_WIDTH = 440;
    private static int lastTab = 0; // reopening Help shows the page you were on

    private final Screen parent;
    private final List<HelpPages.Page> pages = HelpPages.all();
    private final List<PageTab> tabs = new ArrayList<>();
    private final Button doneButton = Button.builder(Component.literal("Done"), _ -> this.onClose())
            .tooltip(Tooltip.create(Component.literal("Back to the settings"))).size(100, 20).build();

    public HelpScreen() {
        super(Component.literal("Help"));
        this.parent = Objects.requireNonNull(Minecraft.getInstance().gui.screen());
        for (HelpPages.Page page : pages) tabs.add(new PageTab(page));
    }

    @Override
    protected void init() {
        TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget,
                tab -> lastTab = Math.max(0, tabs.indexOf(tab)), _ -> {});
        MenuTabBar bar = MenuTabBar.builder(tabManager, width).addTabs(tabs.toArray(Tab[]::new)).build();
        for (int i = 0; i < pages.size(); i++) bar.setTabTooltip(i, Tooltip.create(Component.literal(pages.get(i).tooltip())));
        addRenderableWidget(bar);
        bar.arrangeElements(width);
        doneButton.setPosition(width / 2 - 50, height - 26);
        addRenderableWidget(doneButton);
        bar.selectTab(Math.min(lastTab, tabs.size() - 1), false);
        int top = bar.getRectangle().bottom();
        tabManager.setTabArea(new ScreenRectangle(0, top, width, height - 30 - top));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    /** One page: the text in the card style, scrolling, and its "open this setting" buttons in a row under it. */
    private static final class PageTab implements Tab {
        private final HelpPages.Page page;
        private final CardView view = new CardView(Minecraft.getInstance().font);
        private final List<Button> buttons = new ArrayList<>();
        private final FrameLayout layout = new FrameLayout();

        PageTab(HelpPages.Page page) {
            this.page = page;
            view.setBlocks(page.blocks());
            for (HelpPages.Link link : page.links()) {
                buttons.add(Button.builder(Component.literal(link.label()), _ -> Minecraft.getInstance().gui.setScreen(link.screen().get()))
                        .tooltip(Tooltip.create(Component.literal("Open this setting: " + link.tooltip()))).build());
            }
        }

        @Override
        public Component getTabTitle() {
            return Component.literal(page.title());
        }

        @Override
        public Component getTabExtraNarration() {
            return Component.literal(page.tooltip());
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(view);
            buttons.forEach(consumer);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            int w = Math.min(PAGE_WIDTH, area.width() - 24);
            int x = area.left() + (area.width() - w) / 2;
            int buttonsY = area.bottom() - 22;
            int pageTop = area.top() + 6;
            view.setRectangle(w, Math.max(40, buttonsY - 6 - pageTop), x, pageTop);
            if (buttons.isEmpty()) return;
            int gap = 4;
            int each = Math.min(140, (w - gap * (buttons.size() - 1)) / buttons.size());
            int rowWidth = each * buttons.size() + gap * (buttons.size() - 1);
            int bx = area.left() + (area.width() - rowWidth) / 2;
            for (Button button : buttons) {
                button.setRectangle(each, 20, bx, buttonsY);
                bx += each + gap;
            }
        }

        @Override
        public Layout getLayout() {
            return layout;
        }
    }
}
