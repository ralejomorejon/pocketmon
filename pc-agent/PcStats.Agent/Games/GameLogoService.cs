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
        if (game.SteamAppId is { } appId) _ = DownloadAsync(game.Id, appId);
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

    /// <summary>Downloads logos for the catalog's Steam games that aren't cached yet.</summary>
    public async Task PrefetchAsync(IEnumerable<GameInfo> games, CancellationToken ct)
    {
        foreach (var g in games)
        {
            if (ct.IsCancellationRequested) return;
            if (g.SteamAppId is { } appId && FindFile(g) is null) await DownloadAsync(g.Id, appId);
        }
    }

    private async Task DownloadAsync(string id, int appId)
    {
        if (!SafeId.IsMatch(id)) return;
        if (_failedAt.TryGetValue(id, out var failed) && DateTime.UtcNow - failed < TimeSpan.FromMinutes(30)) return;
        if (!_inFlight.TryAdd(id, 0)) return;
        try
        {
            var bytes = await Http.GetByteArrayAsync($"https://cdn.cloudflare.steamstatic.com/steam/apps/{appId}/logo.png");
            if (bytes.Length < 8 || bytes[0] != 0x89 || bytes[1] != (byte)'P') throw new InvalidDataException("Not a PNG");
            Directory.CreateDirectory(_cacheDir);
            var tmp = Path.Combine(_cacheDir, id + ".tmp");
            await File.WriteAllBytesAsync(tmp, bytes);
            File.Move(tmp, Path.Combine(_cacheDir, id + ".png"), overwrite: true);
            _log.LogInformation("Downloaded logo for {Id} (Steam app {AppId})", id, appId);
        }
        catch (Exception ex)
        {
            _failedAt[id] = DateTime.UtcNow;
            _log.LogInformation("No logo for {Id} (Steam app {AppId}): {Message}", id, appId, ex.Message);
        }
        finally
        {
            _inFlight.TryRemove(id, out _);
        }
    }
}
