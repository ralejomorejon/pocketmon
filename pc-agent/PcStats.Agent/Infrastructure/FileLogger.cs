using System.Collections.Concurrent;
using Microsoft.Extensions.Logging;

namespace PcStats.Agent.Infrastructure;

/// <summary>Minimal file logger writing to %AppData%\PcStatsAgent\logs\agent-yyyyMMdd.log.</summary>
public sealed class FileLoggerProvider : ILoggerProvider
{
    private readonly string _directory;
    private readonly LogLevel _minLevel;
    private readonly BlockingCollection<string> _queue = new(4096);
    private readonly Thread _writer;

    public FileLoggerProvider(string directory, LogLevel minLevel = LogLevel.Information)
    {
        _directory = directory;
        _minLevel = minLevel;
        Directory.CreateDirectory(directory);
        _writer = new Thread(WriteLoop) { IsBackground = true, Name = "File logger" };
        _writer.Start();
        PruneOldLogs();
    }

    public string CurrentFile => Path.Combine(_directory, $"agent-{DateTime.Now:yyyyMMdd}.log");

    public ILogger CreateLogger(string categoryName) => new FileLogger(this, categoryName);

    private void Enqueue(string line) => _queue.TryAdd(line);

    private void WriteLoop()
    {
        foreach (var line in _queue.GetConsumingEnumerable())
        {
            try
            {
                File.AppendAllText(CurrentFile, line + Environment.NewLine);
            }
            catch
            {
                // Logging must never take the agent down.
            }
        }
    }

    private void PruneOldLogs()
    {
        try
        {
            foreach (var f in Directory.GetFiles(_directory, "agent-*.log")
                         .Where(f => File.GetLastWriteTimeUtc(f) < DateTime.UtcNow.AddDays(-14)))
                File.Delete(f);
        }
        catch
        {
        }
    }

    public void Dispose()
    {
        _queue.CompleteAdding();
        _writer.Join(TimeSpan.FromSeconds(2));
    }

    private sealed class FileLogger(FileLoggerProvider provider, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel >= provider._minLevel && logLevel != LogLevel.None;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel)) return;
            var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{logLevel.ToString()[..4].ToUpperInvariant()}] {category}: {formatter(state, exception)}";
            if (exception is not null) line += Environment.NewLine + exception;
            provider.Enqueue(line);
        }
    }
}
