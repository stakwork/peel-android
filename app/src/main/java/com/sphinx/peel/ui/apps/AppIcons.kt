package com.sphinx.peel.ui.apps

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.LucideNames

/// An app's icon (iOS `WorkspaceAppIcon`): the manifest's lucide name drawn as its Material counterpart, the grid
/// for none or one with no counterpart. Name resolution is `LucideNames` (pure, tested); this is the table of
/// vectors. Drawn in the surrounding tint, in a fixed square, like iOS's 20 pt glyph in a 30 pt frame.
object AppIcons {
    val table: Map<String, ImageVector> by lazy {
        mapOf(
            "activity" to Icons.Filled.ShowChart, "alarm-clock" to Icons.Filled.Alarm, "bell" to Icons.Filled.Notifications,
            "book" to Icons.Filled.Book, "book-open" to Icons.Filled.MenuBook, "brain" to Icons.Filled.Psychology,
            "briefcase" to Icons.Filled.Work, "building" to Icons.Filled.Business, "building-complex" to Icons.Filled.LocationCity,
            "calendar" to Icons.Filled.CalendarMonth, "camera" to Icons.Filled.CameraAlt, "chart-column" to Icons.Filled.BarChart,
            "chart-line" to Icons.Filled.ShowChart, "chart-pie" to Icons.Filled.PieChart, "square-check" to Icons.Filled.CheckBox,
            "square-check-big" to Icons.Filled.CheckBox, "circle-dollar-sign" to Icons.Filled.MonetizationOn,
            "clock" to Icons.Filled.Schedule, "code" to Icons.Filled.Code, "cpu" to Icons.Filled.Memory,
            "credit-card" to Icons.Filled.CreditCard, "database" to Icons.Filled.Storage, "dollar-sign" to Icons.Filled.AttachMoney,
            "download" to Icons.Filled.Download, "eye" to Icons.Filled.Visibility, "file" to Icons.Filled.InsertDriveFile,
            "file-text" to Icons.Filled.Description, "flag" to Icons.Filled.Flag, "flask-conical" to Icons.Filled.Science,
            "flask-round" to Icons.Filled.Science, "folder" to Icons.Filled.Folder, "funnel" to Icons.Filled.FilterList,
            "gauge" to Icons.Filled.Speed, "git-branch" to Icons.Filled.AccountTree, "globe" to Icons.Filled.Public,
            "graduation-cap" to Icons.Filled.School, "heart" to Icons.Filled.Favorite, "house" to Icons.Filled.Home,
            "image" to Icons.Filled.Image, "inbox" to Icons.Filled.Inbox, "kanban" to Icons.Filled.ViewKanban,
            "layers" to Icons.Filled.Layers, "layout-grid" to Icons.Filled.Apps, "lightbulb" to Icons.Filled.Lightbulb,
            "link" to Icons.Filled.Link, "list" to Icons.AutoMirrored.Filled.List, "list-checks" to Icons.Filled.Checklist,
            "list-todo" to Icons.Filled.Checklist, "lock" to Icons.Filled.Lock, "mail" to Icons.Filled.Mail,
            "map" to Icons.Filled.Map, "map-pin" to Icons.Filled.Place, "message-circle" to Icons.Filled.ChatBubble,
            "message-square" to Icons.Filled.Chat, "music" to Icons.Filled.MusicNote, "network" to Icons.Filled.Hub,
            "newspaper" to Icons.Filled.Newspaper, "package" to Icons.Filled.Inventory2, "palette" to Icons.Filled.Palette,
            "pen" to Icons.Filled.Edit, "pencil" to Icons.Filled.Edit, "phone" to Icons.Filled.Phone, "plus" to Icons.Filled.Add,
            "puzzle" to Icons.Filled.Extension, "receipt" to Icons.Filled.Receipt, "scale" to Icons.Filled.Balance,
            "search" to Icons.Filled.Search, "server" to Icons.Filled.Dns, "settings" to Icons.Filled.Settings,
            "share" to Icons.Filled.Share, "shield" to Icons.Filled.Shield, "shield-check" to Icons.Filled.VerifiedUser,
            "shopping-cart" to Icons.Filled.ShoppingCart, "sparkles" to Icons.Filled.AutoAwesome, "star" to Icons.Filled.Star,
            "table" to Icons.Filled.TableChart, "tag" to Icons.Filled.Sell, "target" to Icons.Filled.GpsFixed,
            "terminal" to Icons.Filled.Terminal, "test-tube" to Icons.Filled.Science, "trash" to Icons.Filled.Delete,
            "trending-up" to Icons.Filled.TrendingUp, "trophy" to Icons.Filled.EmojiEvents, "truck" to Icons.Filled.LocalShipping,
            "upload" to Icons.Filled.Upload, "user" to Icons.Filled.Person, "users" to Icons.Filled.Group,
            "utensils" to Icons.Filled.Restaurant, "wallet" to Icons.Filled.AccountBalanceWallet, "workflow" to Icons.Filled.AccountTree,
            "zap" to Icons.Filled.Bolt,
        )
    }

    fun vector(name: String?): ImageVector = table[LucideNames.shape(name)] ?: Icons.Filled.Apps
}

@Composable
fun WorkspaceAppIcon(name: String?, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Icon(AppIcons.vector(name), null, modifier.size(size), tint = tint)
}
