using System.Net;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.WebUtilities;
using PcStats.Agent.Config;

namespace PcStats.Agent.Pairing;

public enum PairResult
{
    Success,
    BadCode,
    RateLimited,
}

/// <summary>Owns the rotating 6-digit pairing code and the authorized-token list.</summary>
public sealed class PairingService : IDisposable
{
    public static readonly TimeSpan CodeLifetime = TimeSpan.FromMinutes(5);

    private readonly SettingsStore _store;
    private readonly AttemptRateLimiter _limiter;
    private readonly object _lock = new();
    private readonly System.Threading.Timer _timer;
    private string _code = "";

    public event Action<string>? CodeChanged;
    public event Action? TokensRevoked;

    public PairingService(SettingsStore store, AttemptRateLimiter? limiter = null)
    {
        _store = store;
        _limiter = limiter ?? new AttemptRateLimiter(5, TimeSpan.FromMinutes(1));
        RegenerateCode();
        _timer = new System.Threading.Timer(_ => RegenerateCode(), null, CodeLifetime, CodeLifetime);
    }

    public string CurrentCode
    {
        get { lock (_lock) return _code; }
    }

    public void RegenerateCode()
    {
        string code;
        lock (_lock)
        {
            _code = code = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
            _timer?.Change(CodeLifetime, CodeLifetime);
        }
        CodeChanged?.Invoke(code);
    }

    /// <summary>Validates a pairing code; on success returns a new raw token (only its hash is stored).</summary>
    public PairResult TryPair(IPAddress? remote, string? code, out string? token)
    {
        token = null;
        if (!_limiter.TryAcquire(remote?.ToString() ?? "unknown")) return PairResult.RateLimited;

        bool ok;
        lock (_lock)
        {
            ok = FixedTimeEquals(code ?? "", _code);
        }
        if (!ok) return PairResult.BadCode;

        token = GenerateToken();
        _store.AddTokenHash(HashToken(token));
        RegenerateCode();
        return PairResult.Success;
    }

    public bool IsAuthorized(string? token)
    {
        if (string.IsNullOrEmpty(token)) return false;
        var hash = HashToken(token);
        var match = false;
        foreach (var stored in _store.TokenHashes)
            match |= FixedTimeEquals(hash, stored);
        return match;
    }

    public void RevokeAll()
    {
        _store.ClearTokens();
        RegenerateCode();
        TokensRevoked?.Invoke();
    }

    public static string GenerateToken() => WebEncoders.Base64UrlEncode(RandomNumberGenerator.GetBytes(32));

    public static string HashToken(string token) =>
        WebEncoders.Base64UrlEncode(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    private static bool FixedTimeEquals(string a, string b) =>
        CryptographicOperations.FixedTimeEquals(
            SHA256.HashData(Encoding.UTF8.GetBytes(a)),
            SHA256.HashData(Encoding.UTF8.GetBytes(b)));

    public void Dispose() => _timer.Dispose();
}

/// <summary>Sliding-window limiter: at most <c>max</c> attempts per key per window.</summary>
public sealed class AttemptRateLimiter
{
    private readonly int _max;
    private readonly TimeSpan _window;
    private readonly Func<DateTime> _clock;
    private readonly Dictionary<string, Queue<DateTime>> _attempts = new();

    public AttemptRateLimiter(int max, TimeSpan window, Func<DateTime>? clock = null)
    {
        _max = max;
        _window = window;
        _clock = clock ?? (() => DateTime.UtcNow);
    }

    public bool TryAcquire(string key)
    {
        lock (_attempts)
        {
            var now = _clock();
            if (!_attempts.TryGetValue(key, out var q)) _attempts[key] = q = new Queue<DateTime>();
            while (q.Count > 0 && now - q.Peek() >= _window) q.Dequeue();
            if (q.Count >= _max) return false;
            q.Enqueue(now);

            if (_attempts.Count > 1024)
            {
                foreach (var k in _attempts.Where(kv => kv.Value.All(t => now - t >= _window)).Select(kv => kv.Key).ToList())
                    _attempts.Remove(k);
            }
            return true;
        }
    }
}
