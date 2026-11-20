using System.Text.Json;
using System.Text.RegularExpressions;

namespace PcStats.Agent.Games;

/// <param name="LogoUrl">Direct download URL for the logo (PNG/WebP/JPEG) when it is not on Steam.</param>
/// <param name="FpsFonts">FPS counter font ids in order of preference (first one available wins), e.g. ["hylia-serif","cinzel"].</param>
/// <param name="FpsColor">FPS counter colour as #RRGGBB; null = default.</param>
public sealed record GameInfo(string Id, string Name, int? SteamAppId = null, string? BundledLogo = null, string? LogoUrl = null,
    IReadOnlyList<string>? FpsFonts = null, string? FpsColor = null);

/// <summary>Identifies the foreground game from its exe name, Steam install folder, or (for emulators) window title.</summary>
public sealed class GameCatalog
{
    private sealed record Entry(GameInfo Info, HashSet<string> Exes, Regex? Title);

    private readonly List<Entry> _entries = [];
    private readonly HashSet<string> _emulators = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, Dictionary<string, (int AppId, string Name)>> _steamLibraries = new(StringComparer.OrdinalIgnoreCase);

    public IEnumerable<GameInfo> Games => _entries.Select(e => e.Info);

    /// <summary>Loads JSON catalogs in order; later files override entries with the same id.</summary>
    public static GameCatalog Load(params string[] paths)
    {
        var catalog = new GameCatalog();
        foreach (var p in paths.Where(File.Exists))
        {
            try
            {
                catalog.AddJson(File.ReadAllText(p));
            }
            catch (Exception)
            {
                // A broken user catalog must not stop the agent; the bundled one still applies.
            }
        }
        return catalog;
    }

    public void AddJson(string json)
    {
        using var doc = JsonDocument.Parse(json, new JsonDocumentOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip });
        var root = doc.RootElement;
        if (root.TryGetProperty("emulators", out var emus))
        {
            foreach (var e in emus.EnumerateArray()) _emulators.Add(e.GetString() ?? "");
        }
        if (!root.TryGetProperty("games", out var games)) return;
        foreach (var g in games.EnumerateArray())
        {
            var id = g.GetProperty("id").GetString()!;
            var info = new GameInfo(
                id,
                g.TryGetProperty("name", out var n) ? n.GetString() ?? id : id,
                g.TryGetProperty("steamAppId", out var s) && s.TryGetInt32(out var appId) ? appId : null,
                g.TryGetProperty("logo", out var l) ? l.GetString() : null,
                g.TryGetProperty("logoUrl", out var u) && Uri.TryCreate(u.GetString(), UriKind.Absolute, out var uri) && uri.Scheme == Uri.UriSchemeHttps ? uri.ToString() : null,
                ReadFonts(g),
                g.TryGetProperty("fpsColor", out var fc) && fc.GetString() is { } color && Regex.IsMatch(color, "^#[0-9A-Fa-f]{6}$") ? color : null);
            var exes = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            if (g.TryGetProperty("exe", out var ex)) foreach (var e in ex.EnumerateArray()) exes.Add(e.GetString() ?? "");
            var title = g.TryGetProperty("title", out var t) && t.GetString() is { Length: > 0 } pattern
                ? new Regex(pattern, RegexOptions.CultureInvariant, TimeSpan.FromMilliseconds(50))
                : null;
            _entries.RemoveAll(e => e.Info.Id == id);
            _entries.Add(new Entry(info, exes, title));
        }
    }

    private static IReadOnlyList<string>? ReadFonts(JsonElement g)
    {
        if (!g.TryGetProperty("fpsFont", out var ff)) return null;
        var ids = ff.ValueKind == JsonValueKind.Array
            ? ff.EnumerateArray().Select(e => e.GetString()).ToList()
            : [ff.GetString()];
        var valid = ids.OfType<string>().Where(Fonts.FontService.IsSafeId).ToList();
        return valid.Count > 0 ? valid : null;
    }

    public GameInfo? Identify(string? exeName, string? exePath, string? windowTitle)
    {
        var exe = exeName ?? (exePath is null ? null : Path.GetFileName(exePath));
        if (exe is not null)
        {
            var byExe = _entries.FirstOrDefault(e => e.Exes.Contains(exe));
            if (byExe is not null) return byExe.Info;
        }

        if (exePath is not null && IdentifySteam(exePath) is { } steam) return steam;

        if (exe is not null && _emulators.Contains(exe) && !string.IsNullOrWhiteSpace(windowTitle))
        {
            foreach (var e in _entries)
            {
                try
                {
                    if (e.Title?.IsMatch(windowTitle) == true) return e.Info;
                }
                catch (RegexMatchTimeoutException)
                {
                    // Ignore pathological titles.
                }
            }
        }
        return null;
    }

    /// <summary>Any Steam game: map ...\steamapps\common\&lt;installdir&gt;\... to its appmanifest.</summary>
    private GameInfo? IdentifySteam(string exePath)
    {
        const string marker = @"\steamapps\common\";
        var i = exePath.IndexOf(marker, StringComparison.OrdinalIgnoreCase);
        if (i < 0) return null;
        var steamapps = exePath[..(i + @"\steamapps".Length)];
        var installDir = exePath[(i + marker.Length)..].Split('\\')[0];

        if (!_steamLibraries.TryGetValue(steamapps, out var map))
        {
            map = ReadLibrary(steamapps);
            _steamLibraries[steamapps] = map;
        }
        if (!map.TryGetValue(installDir, out var app)) return null;
        var known = _entries.FirstOrDefault(e => e.Info.SteamAppId == app.AppId);
        return known?.Info ?? new GameInfo($"steam-{app.AppId}", app.Name, app.AppId);
    }

    private static Dictionary<string, (int, string)> ReadLibrary(string steamapps)
    {
        var map = new Dictionary<string, (int, string)>(StringComparer.OrdinalIgnoreCase);
        try
        {
            foreach (var f in Directory.EnumerateFiles(steamapps, "appmanifest_*.acf"))
            {
                if (ParseAppManifest(File.ReadAllText(f)) is { } m) map[m.InstallDir] = (m.AppId, m.Name);
            }
        }
        catch (Exception)
        {
            // Unreadable library: no Steam detection for it.
        }
        return map;
    }

    private static readonly Regex AcfField = new("^\\s*\"(appid|name|installdir)\"\\s+\"([^\"]*)\"", RegexOptions.Multiline | RegexOptions.IgnoreCase);

    internal static (int AppId, string Name, string InstallDir)? ParseAppManifest(string acf)
    {
        string? id = null, name = null, dir = null;
        foreach (Match m in AcfField.Matches(acf))
        {
            var key = m.Groups[1].Value.ToLowerInvariant();
            var val = m.Groups[2].Value;
            if (key == "appid") id ??= val;
            else if (key == "name") name ??= val;
            else dir ??= val;
        }
        return int.TryParse(id, out var appId) && dir is not null ? (appId, name ?? dir, dir) : null;
    }
}
