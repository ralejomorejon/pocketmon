using System.Text.RegularExpressions;

namespace PcStats.Agent.Fonts;

/// <summary>
/// Fonts for the phone's FPS counter. "orbitron" and "cinzel" ship inside the apps; any other id is a font file the user
/// placed in %AppData%\PcStatsAgent\fonts (&lt;id&gt;.otf/.ttf) and is served to phones at /fonts/&lt;id&gt;. Such fonts
/// stay on the user's PC because their licences may not allow redistribution with PocketMon.
/// </summary>
public sealed partial class FontService
{
    private static readonly string[] BuiltIn = ["orbitron", "cinzel"];
    private readonly string _dir;

    [GeneratedRegex("^[a-z0-9][a-z0-9-]{0,31}$")]
    private static partial Regex SafeIdRegex { get; }

    public FontService(string dir) => _dir = dir;

    public static bool IsSafeId(string? id) => id is not null && SafeIdRegex.IsMatch(id);

    /// <summary>First font in <paramref name="preferred"/> that phones can use, or null for the default.</summary>
    public string? Resolve(IReadOnlyList<string>? preferred) =>
        preferred?.FirstOrDefault(id => BuiltIn.Contains(id) || FindFile(id) is not null);

    public string? FindFile(string id)
    {
        if (!IsSafeId(id)) return null;
        foreach (var ext in new[] { ".otf", ".ttf" })
        {
            var path = Path.Combine(_dir, id + ext);
            if (File.Exists(path)) return path;
        }
        return null;
    }

    public static string ContentType(string path) => path.EndsWith(".otf", StringComparison.OrdinalIgnoreCase) ? "font/otf" : "font/ttf";
}
