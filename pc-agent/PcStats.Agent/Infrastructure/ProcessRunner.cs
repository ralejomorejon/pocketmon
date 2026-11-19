using System.Diagnostics;

namespace PcStats.Agent.Infrastructure;

public static class ProcessRunner
{
    public sealed record Result(int ExitCode, string Output);

    /// <summary>Runs a console program hidden and captures stdout+stderr. Returns exit code -1 on failure to start/timeout.</summary>
    public static Result Run(string fileName, IEnumerable<string> args, TimeSpan? timeout = null)
    {
        var psi = new ProcessStartInfo(fileName)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);

        try
        {
            using var p = Process.Start(psi);
            if (p is null) return new Result(-1, "");
            var stdout = p.StandardOutput.ReadToEndAsync();
            var stderr = p.StandardError.ReadToEndAsync();
            if (!p.WaitForExit(timeout ?? TimeSpan.FromSeconds(15)))
            {
                try
                {
                    p.Kill(true);
                }
                catch
                {
                }
                return new Result(-1, "timeout");
            }
            p.WaitForExit();
            return new Result(p.ExitCode, stdout.Result + stderr.Result);
        }
        catch (Exception ex)
        {
            return new Result(-1, ex.Message);
        }
    }
}
