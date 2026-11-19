using System.Text.Json;
using PcStats.Agent.Protocol;

namespace PcStats.Agent.Config;

public sealed class AgentSettings
{
    public string PcId { get; set; } = Guid.NewGuid().ToString();

    /// <summary>SHA-256 hashes (base64url) of issued tokens; raw tokens are never stored.</summary>
    public List<string> AuthorizedTokenHashes { get; set; } = [];

    public int IntervalMs { get; set; } = ProtocolConstants.DefaultIntervalMs;
}

/// <summary>Loads and saves %AppData%\PcStatsAgent\config.json. Thread-safe.</summary>
public sealed class SettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    private readonly object _lock = new();
    private readonly string _path;
    private AgentSettings _settings;

    public static string DefaultDirectory { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PcStatsAgent");

    public SettingsStore(string? directory = null)
    {
        var dir = directory ?? DefaultDirectory;
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "config.json");
        _settings = Load(_path);
        if (!Guid.TryParse(_settings.PcId, out _)) _settings.PcId = Guid.NewGuid().ToString();
        _settings.IntervalMs = ProtocolConstants.ClampInterval(_settings.IntervalMs);
        Save();
    }

    public string PcId
    {
        get { lock (_lock) return _settings.PcId; }
    }

    public int IntervalMs
    {
        get { lock (_lock) return _settings.IntervalMs; }
    }

    public IReadOnlyList<string> TokenHashes
    {
        get { lock (_lock) return _settings.AuthorizedTokenHashes.ToList(); }
    }

    public void AddTokenHash(string hash)
    {
        lock (_lock)
        {
            _settings.AuthorizedTokenHashes.Add(hash);
            Save();
        }
    }

    public void ClearTokens()
    {
        lock (_lock)
        {
            _settings.AuthorizedTokenHashes.Clear();
            Save();
        }
    }

    private static AgentSettings Load(string path)
    {
        try
        {
            if (File.Exists(path))
                return JsonSerializer.Deserialize<AgentSettings>(File.ReadAllText(path), JsonOptions) ?? new AgentSettings();
        }
        catch (Exception)
        {
            // A corrupt config is replaced; phones simply need to pair again.
        }
        return new AgentSettings();
    }

    private void Save()
    {
        lock (_lock)
        {
            var tmp = _path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(_settings, JsonOptions));
            File.Move(tmp, _path, overwrite: true);
        }
    }
}
