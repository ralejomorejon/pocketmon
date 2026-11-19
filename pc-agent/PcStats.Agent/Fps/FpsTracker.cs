namespace PcStats.Agent.Fps;

/// <summary>
/// Parses PresentMon CSV lines and computes per-process FPS. Thread-safe and clock-injected for tests.
/// Each swap chain gets its own frame clock built from the frame-interval column, so bursty stdout
/// delivery does not distort the result.
/// </summary>
public sealed class FpsTracker
{
    public const double WindowMs = 1000;
    public const long StaleAfterMs = 2000;
    private const long ForgetAfterMs = 10_000;

    private static readonly string[] IntervalColumns = ["FrameTime", "MsBetweenPresents", "MsBetweenAppStart"];

    private readonly object _lock = new();
    private readonly Dictionary<(int Pid, string Chain), ChainState> _chains = new();
    private Dictionary<string, int>? _columns;
    private int _appCol = -1, _pidCol = -1, _chainCol = -1, _intervalCol = -1;

    public bool HasHeader
    {
        get { lock (_lock) return _columns is not null; }
    }

    /// <summary>Forget the header and all frames (e.g. when PresentMon restarts).</summary>
    public void Reset()
    {
        lock (_lock)
        {
            _columns = null;
            _chains.Clear();
        }
    }

    public void ProcessLine(string line, long nowMs)
    {
        if (string.IsNullOrWhiteSpace(line)) return;
        var fields = SplitCsv(line);

        lock (_lock)
        {
            if (LooksLikeHeader(fields))
            {
                SetHeader(fields);
                return;
            }
            if (_columns is null || _pidCol < 0 || _pidCol >= fields.Count) return;
            if (!int.TryParse(fields[_pidCol], out var pid)) return;

            var app = _appCol >= 0 && _appCol < fields.Count ? fields[_appCol] : "";
            var chain = _chainCol >= 0 && _chainCol < fields.Count ? fields[_chainCol] : "";
            var key = (pid, chain);

            if (!_chains.TryGetValue(key, out var state))
            {
                state = new ChainState { ClockMs = 0 };
                _chains[key] = state;
            }
            else if (_intervalCol < 0)
            {
                // No interval column: fall back to arrival time.
                state.ClockMs += Math.Max(0, nowMs - state.LastArrivalMs);
            }
            else if (_intervalCol < fields.Count
                     && double.TryParse(fields[_intervalCol], System.Globalization.NumberStyles.Float,
                         System.Globalization.CultureInfo.InvariantCulture, out var interval)
                     && interval > 0 && double.IsFinite(interval))
            {
                state.ClockMs += interval;
            }

            state.App = app;
            state.LastArrivalMs = nowMs;
            state.Frames.Enqueue(state.ClockMs);
            while (state.Frames.Count > 0 && state.Frames.Peek() < state.ClockMs - StaleAfterMs)
                state.Frames.Dequeue();

            if (_chains.Count > 64) Prune(nowMs);
        }
    }

    /// <summary>FPS over the last second for <paramref name="pid"/>, or null if it has no frames in the last 2 s.</summary>
    public (string App, double Fps)? GetFps(int pid, long nowMs)
    {
        lock (_lock)
        {
            Prune(nowMs);
            (string App, double Fps)? best = null;
            var bestCount = 0;

            foreach (var ((chainPid, _), state) in _chains)
            {
                if (chainPid != pid || nowMs - state.LastArrivalMs > StaleAfterMs) continue;

                var windowStart = state.ClockMs - WindowMs;
                int count = 0;
                double first = double.NaN;
                foreach (var t in state.Frames)
                {
                    if (t <= windowStart) continue;
                    if (count == 0) first = t;
                    count++;
                }
                if (count == 0 || count < bestCount) continue;

                var span = state.ClockMs - first;
                var fps = count >= 2 && span > 0 ? (count - 1) * 1000.0 / span : count * 1000.0 / WindowMs;
                bestCount = count;
                best = (state.App, Math.Round(fps, 1));
            }
            return best;
        }
    }

    private void Prune(long nowMs)
    {
        foreach (var key in _chains.Where(kv => nowMs - kv.Value.LastArrivalMs > ForgetAfterMs).Select(kv => kv.Key).ToList())
            _chains.Remove(key);
    }

    private static bool LooksLikeHeader(List<string> fields) =>
        fields.Any(f => f.Equals("ProcessID", StringComparison.OrdinalIgnoreCase))
        && fields.Any(f => f.Equals("Application", StringComparison.OrdinalIgnoreCase));

    private void SetHeader(List<string> fields)
    {
        _columns = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        for (var i = 0; i < fields.Count; i++) _columns.TryAdd(fields[i].Trim(), i);
        _appCol = _columns.GetValueOrDefault("Application", -1);
        _pidCol = _columns.GetValueOrDefault("ProcessID", -1);
        _chainCol = _columns.GetValueOrDefault("SwapChainAddress", -1);
        _intervalCol = -1;
        foreach (var name in IntervalColumns)
        {
            if (_columns.TryGetValue(name, out var idx))
            {
                _intervalCol = idx;
                break;
            }
        }
        _chains.Clear();
    }

    internal static List<string> SplitCsv(string line)
    {
        var result = new List<string>();
        var sb = new System.Text.StringBuilder();
        var inQuotes = false;
        for (var i = 0; i < line.Length; i++)
        {
            var c = line[i];
            if (inQuotes)
            {
                if (c == '"' && i + 1 < line.Length && line[i + 1] == '"') { sb.Append('"'); i++; }
                else if (c == '"') inQuotes = false;
                else sb.Append(c);
            }
            else if (c == '"') inQuotes = true;
            else if (c == ',') { result.Add(sb.ToString()); sb.Clear(); }
            else if (c != '\r' && c != '\n') sb.Append(c);
        }
        result.Add(sb.ToString());
        return result;
    }

    private sealed class ChainState
    {
        public double ClockMs;
        public long LastArrivalMs;
        public string App = "";
        public readonly Queue<double> Frames = new();
    }
}
