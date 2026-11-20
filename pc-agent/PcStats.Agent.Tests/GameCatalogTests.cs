using PcStats.Agent.Games;

namespace PcStats.Agent.Tests;

public class GameCatalogTests
{
    private static GameCatalog Catalog()
    {
        var c = new GameCatalog();
        c.AddJson(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "games.json")));
        return c;
    }

    [Fact]
    public void Identifies_by_exe_case_insensitively()
    {
        var g = Catalog().Identify("Dota2.EXE", null, "Dota 2");
        Assert.Equal("dota2", g?.Id);
        Assert.Equal(570, g?.SteamAppId);
        Assert.Equal("zelda-oot", Catalog().Identify("soh.exe", null, null)?.Id);
        var hookshot = Catalog().Identify("hookshot.exe", @"C:\Games\Hookshot Ocarina\bin\hookshot.exe", "Hookshot");
        Assert.Equal("zelda-oot-remake", hookshot?.Id);
        Assert.Equal("The Legend of Zelda: Ocarina of Time", hookshot?.Name);
        Assert.StartsWith("https://", hookshot?.LogoUrl);
        Assert.Equal(("cinzel", "#E2B04A"), (hookshot?.FpsFont, hookshot?.FpsColor));
        Assert.Null(Catalog().Identify("dota2.exe", null, null)?.FpsFont);
    }

    [Fact]
    public void Logo_types_are_sniffed_from_content()
    {
        Assert.Equal("image/png", GameLogoService.SniffImageType([0x89, (byte)'P', (byte)'N', (byte)'G', 13, 10, 26, 10]));
        Assert.Equal("image/webp", GameLogoService.SniffImageType("RIFF\0\0\0\0WEBPVP8 "u8.ToArray()));
        Assert.Null(GameLogoService.SniffImageType("<html>"u8.ToArray()));
    }

    [Fact]
    public void Window_title_only_counts_for_emulators()
    {
        var c = Catalog();
        Assert.Equal("zelda-oot", c.Identify("Project64.exe", null, "THE LEGEND OF ZELDA - Project64 3.0")?.Id);
        Assert.Null(c.Identify("Project64.exe", null, "ZELDA MAJORA'S MASK - Project64"));
        // A browser showing an Ocarina of Time page is not the game.
        Assert.Null(c.Identify("chrome.exe", null, "Ocarina of Time walkthrough - Google Chrome"));
    }

    [Fact]
    public void Any_steam_game_is_identified_from_its_app_manifest()
    {
        var lib = Directory.CreateTempSubdirectory();
        try
        {
            var steamapps = Path.Combine(lib.FullName, "steamapps");
            Directory.CreateDirectory(Path.Combine(steamapps, "common", "Hades II"));
            File.WriteAllText(Path.Combine(steamapps, "appmanifest_1145350.acf"),
                "\"AppState\"\n{\n\t\"appid\"\t\t\"1145350\"\n\t\"name\"\t\t\"Hades II\"\n\t\"installdir\"\t\t\"Hades II\"\n}\n");
            var exe = Path.Combine(steamapps, "common", "Hades II", "Ship", "Hades2.exe");

            var g = Catalog().Identify("Hades2.exe", exe, null);

            Assert.Equal(new GameInfo("steam-1145350", "Hades II", 1145350), g);
        }
        finally
        {
            lib.Delete(true);
        }
    }

    [Fact]
    public void Unknown_apps_are_not_games()
    {
        Assert.Null(Catalog().Identify("notepad.exe", @"C:\Windows\notepad.exe", "Untitled - Notepad"));
    }

    [Fact]
    public void Logo_service_rejects_unsafe_ids_and_serves_bundled_logo()
    {
        var logos = new GameLogoService(Microsoft.Extensions.Logging.Abstractions.NullLogger.Instance,
            Path.Combine(Path.GetTempPath(), "pm-logo-test-" + Guid.NewGuid()), Path.Combine(AppContext.BaseDirectory, "logos"));
        var c = Catalog();
        Assert.Null(logos.GetFile("..\\secret", c));
        Assert.EndsWith("zelda-oot.png", logos.GetFile("zelda-oot", c));
        Assert.Equal("/logos/zelda-oot.png", logos.GetLogoUrl(c.Games.First(g => g.Id == "zelda-oot")));
    }
}
