using PcStats.Agent.Fps;

namespace PcStats.Agent.Tests;

public class FpsTrackerTests
{
    private const string V2Header =
        "Application,ProcessID,SwapChainAddress,PresentRuntime,SyncInterval,PresentFlags,AllowsTearing,PresentMode,CPUStartTime,FrameTime,CPUBusy,CPUWait";

    private static string Row(string app, int pid, double frameTimeMs, string chain = "0x1") =>
        $"{app},{pid},{chain},DXGI,0,0,1,Hardware: Independent Flip,1.0,{frameTimeMs.ToString(System.Globalization.CultureInfo.InvariantCulture)},1,1";

    [Fact]
    public void Computes_fps_from_frame_times_over_last_second()
    {
        var t = new FpsTracker();
        t.ProcessLine(V2Header, 0);
        for (var i = 0; i < 300; i++) t.ProcessLine(Row("game.exe", 42, 1000.0 / 144), 1000);

        var fps = t.GetFps(42, 1000);

        Assert.NotNull(fps);
        Assert.Equal("game.exe", fps.Value.App);
        Assert.InRange(fps.Value.Fps, 143.5, 144.5);
    }

    [Fact]
    public void Rows_before_header_are_ignored_and_unknown_pid_returns_null()
    {
        var t = new FpsTracker();
        t.ProcessLine(Row("game.exe", 42, 10), 0);
        Assert.False(t.HasHeader);
        Assert.Null(t.GetFps(42, 0));

        t.ProcessLine(V2Header, 0);
        t.ProcessLine(Row("game.exe", 42, 10), 0);
        Assert.Null(t.GetFps(7, 0));
    }

    [Fact]
    public void Returns_null_when_pid_has_no_frames_in_last_two_seconds()
    {
        var t = new FpsTracker();
        t.ProcessLine(V2Header, 0);
        for (var i = 0; i < 60; i++) t.ProcessLine(Row("game.exe", 42, 16.6), 5000);

        Assert.NotNull(t.GetFps(42, 6999));
        Assert.Null(t.GetFps(42, 7001));
    }

    [Fact]
    public void Header_is_parsed_dynamically_with_v1_column_names()
    {
        var t = new FpsTracker();
        t.ProcessLine("Application,ProcessID,SwapChainAddress,Runtime,TimeInSeconds,MsBetweenPresents", 0);
        for (var i = 0; i < 100; i++) t.ProcessLine($"old.exe,9,0x2,DXGI,0,20", 0);

        var fps = t.GetFps(9, 0);
        Assert.NotNull(fps);
        Assert.InRange(fps.Value.Fps, 49.5, 50.5);
    }

    [Fact]
    public void Busiest_swap_chain_of_a_process_is_reported()
    {
        var t = new FpsTracker();
        t.ProcessLine(V2Header, 0);
        for (var i = 0; i < 120; i++) t.ProcessLine(Row("game.exe", 1, 1000.0 / 120, "0xA"), 0);
        for (var i = 0; i < 10; i++) t.ProcessLine(Row("game.exe", 1, 100, "0xB"), 0);

        Assert.InRange(t.GetFps(1, 0)!.Value.Fps, 119, 121);
    }

    [Fact]
    public void Reset_clears_frames_and_header()
    {
        var t = new FpsTracker();
        t.ProcessLine(V2Header, 0);
        t.ProcessLine(Row("game.exe", 42, 10), 0);
        t.Reset();

        Assert.False(t.HasHeader);
        Assert.Null(t.GetFps(42, 0));
    }

    [Fact]
    public void Csv_split_handles_quoted_fields()
    {
        Assert.Equal(["a,b", "2", "x\"y"], FpsTracker.SplitCsv("\"a,b\",2,\"x\"\"y\""));
    }
}
