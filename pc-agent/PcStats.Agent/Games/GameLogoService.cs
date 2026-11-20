using System.Collections.Concurrent;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Logging;

namespace PcStats.Agent.Games;

/// <summary>
/// Resolves game logos: user overrides and the download cache in %AppData%\PcStatsAgent\logos, then logos bundled
/// next to the exe. Steam logos are fetched from the Steam CDN on demand (never redistributed with the app).
/// </summary>
public sealed partial class GameLogoService
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(20) };
    private readonly ILogger _log;
    private readonly string _cacheDir;
    private readonly string _bundledDir;
    private readonly ConcurrentDictionary<string, byte> _inFlight = new();
    private readonly ConcurrentDictionary<string, DateTime> _failedAt = new();

    [GeneratedRegex("^[a-z0-9][a-z0-9-]{0,63}$")]
    private static partial Regex SafeId { get; }

    public GameLogoService(ILogger log, string cacheDir, string bundledDir)
    {
        _log = log;
        _cacheDir = cacheDir;
        _bundledDir = bundledDir;
    }

    /// <summary>URL path of the logo if one is available now; otherwise starts a background download and returns null.</summary>
    public string? GetLogoUrl(GameInfo game)
    {
        if (FindFile(game) is not null) return $"/logos/{game.Id}.png";
        if (SourceUrl(game) is { } url) _ = DownloadAsync(game.Id, url);
        return null;
    }

    private static string? SourceUrl(GameInfo game) =>
        game.LogoUrl ?? (game.SteamAppId is { } appId ? $"https://cdn.cloudflare.steamstatic.com/steam/apps/{appId}/logo.png" : null);

    /// <summary>Content type from the file's magic bytes (logos may be PNG, WebP or JPEG whatever their name).</summary>
    public static string? SniffImageType(ReadOnlySpan<byte> b)
    {
        if (b.Length >= 8 && b[0] == 0x89 && b[1] == (byte)'P' && b[2] == (byte)'N' && b[3] == (byte)'G') return "image/png";
        if (b.Length >= 12 && b[..4].SequenceEqual("RIFF"u8) && b[8..12].SequenceEqual("WEBP"u8)) return "image/webp";
        if (b.Length >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF) return "image/jpeg";
        return null;
    }

    /// <summary>File for /logos/{id}.png requests.</summary>
    public string? GetFile(string id, GameCatalog catalog)
    {
        if (!SafeId.IsMatch(id)) return null;
        var game = catalog.Games.FirstOrDefault(g => g.Id == id) ?? new GameInfo(id, id);
        return FindFile(game);
    }

    private string? FindFile(GameInfo game)
    {
        if (!SafeId.IsMatch(game.Id)) return null;
        var cached = Path.Combine(_cacheDir, game.Id + ".png");
        if (File.Exists(cached)) return cached;
        if (game.BundledLogo is { } name)
        {
            var bundled = Path.Combine(_bundledDir, Path.GetFileName(name));
            if (File.Exists(bundled)) return bundled;
        }
        return null;
    }

    /// <summary>Downloads logos for the catalog's games that have a source and aren't cached yet.</summary>
    public async Task PrefetchAsync(IEnumerable<GameInfo> games, CancellationToken ct)
    {
        foreach (var g in games)
        {
            if (ct.IsCancellationRequested) return;
            if (SourceUrl(g) is { } url && FindFile(g) is null) await DownloadAsync(g.Id, url);
        }
    }

    private async Task DownloadAsync(string id, string url)
    {
        if (!SafeId.IsMatch(id)) return;
        if (_failedAt.TryGetValue(id, out var failed) && DateTime.UtcNow - failed < TimeSpan.FromMinutes(30)) return;
        if (!_inFlight.TryAdd(id, 0)) return;
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            req.Headers.UserAgent.ParseAdd("PocketMon/1.0 (+https://github.com/ralejomorejon/pocketmon)");
            using var res = await Http.SendAsync(req);
            res.EnsureSuccessStatusCode();
            var bytes = await res.Content.ReadAsByteArrayAsync();
            if (bytes.Length > 8 * 1024 * 1024 || SniffImageType(bytes) is null) throw new InvalidDataException("Not a PNG/WebP/JPEG image");
            Directory.CreateDirectory(_cacheDir);
            var tmp = Path.Combine(_cacheDir, id + ".tmp");
            await File.WriteAllBytesAsync(tmp, bytes);
            File.Move(tmp, Path.Combine(_cacheDir, id + ".png"), overwrite: true);
            _log.LogInformation("Downloaded logo for {Id} from {Url}", id, url);
        }
        catch (Exception ex)
        {
            _failedAt[id] = DateTime.UtcNow;
            _log.LogInformation("No logo for {Id} ({Url}): {Message}", id, url, ex.Message);
        }
        finally
        {
            _inFlight.TryRemove(id, out _);
        }
    }
}
