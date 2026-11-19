using System.Management;
using Microsoft.Extensions.Logging;
using PcStats.Agent.Protocol;

namespace PcStats.Agent.Sensors;

/// <summary>Fixed drives with free space; model names come from the Windows Storage WMI provider.</summary>
public sealed class DiskReader
{
    private static readonly TimeSpan ModelRefresh = TimeSpan.FromMinutes(1);

    private readonly ILogger _log;
    private Dictionary<string, string> _modelByLetter = new(StringComparer.OrdinalIgnoreCase);
    private DateTime _modelsReadAt = DateTime.MinValue;

    public DiskReader(ILogger log) => _log = log;

    public List<DiskStats> Read(IReadOnlyDictionary<string, double?>? tempsByModel)
    {
        if (DateTime.UtcNow - _modelsReadAt > ModelRefresh)
        {
            _modelsReadAt = DateTime.UtcNow;
            _modelByLetter = ReadModels();
        }

        var result = new List<DiskStats>();
        DriveInfo[] drives;
        try
        {
            drives = DriveInfo.GetDrives();
        }
        catch (Exception ex)
        {
            _log.LogDebug(ex, "Enumerating drives failed");
            return result;
        }

        foreach (var d in drives)
        {
            try
            {
                if (d.DriveType != DriveType.Fixed || !d.IsReady) continue;
                var letter = d.Name.TrimEnd('\\');
                _modelByLetter.TryGetValue(letter, out var model);
                result.Add(new DiskStats
                {
                    Name = letter,
                    Model = model ?? (string.IsNullOrWhiteSpace(d.VolumeLabel) ? null : d.VolumeLabel),
                    FreeMb = d.AvailableFreeSpace / (1024 * 1024),
                    TotalMb = d.TotalSize / (1024 * 1024),
                    Temp = model is null ? null : MatchTemp(model, tempsByModel),
                });
            }
            catch (Exception ex)
            {
                _log.LogDebug(ex, "Reading drive {Drive} failed", d.Name);
            }
        }
        return result.OrderBy(d => d.Name, StringComparer.OrdinalIgnoreCase).ToList();
    }

    internal static double? MatchTemp(string model, IReadOnlyDictionary<string, double?>? temps)
    {
        if (temps is null || temps.Count == 0) return null;
        if (temps.TryGetValue(model, out var exact)) return exact;
        // WMI and LHM sometimes differ in vendor prefixes or trailing serial-like suffixes.
        foreach (var (name, t) in temps)
        {
            if (name.Contains(model, StringComparison.OrdinalIgnoreCase) || model.Contains(name, StringComparison.OrdinalIgnoreCase))
                return t;
        }
        return null;
    }

    private Dictionary<string, string> ReadModels()
    {
        var map = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            var scope = new ManagementScope(@"\\.\root\Microsoft\Windows\Storage");
            var disks = new Dictionary<uint, string>();
            using (var s = new ManagementObjectSearcher(scope, new ObjectQuery("SELECT Number, FriendlyName FROM MSFT_Disk")))
            {
                foreach (var o in s.Get())
                {
                    if (o["Number"] is uint n && o["FriendlyName"] is string name) disks[n] = name.Trim();
                }
            }
            using (var s = new ManagementObjectSearcher(scope, new ObjectQuery("SELECT DriveLetter, DiskNumber FROM MSFT_Partition")))
            {
                foreach (var o in s.Get())
                {
                    if (o["DriveLetter"] is char c && c != '\0' && o["DiskNumber"] is uint n && disks.TryGetValue(n, out var model))
                        map[$"{c}:"] = model;
                }
            }
        }
        catch (Exception ex)
        {
            _log.LogDebug(ex, "Reading disk models via WMI failed");
        }
        return map;
    }
}
