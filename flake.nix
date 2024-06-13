{
  description = "scala-native";

  inputs = {
    typelevel-nix.url = "github:typelevel/typelevel-nix";
    nixpkgs.follows = "typelevel-nix/nixpkgs";
    flake-utils.follows = "typelevel-nix/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
    typelevel-nix
  }:
  flake-utils.lib.eachDefaultSystem (system:
    let
      pkgs = import nixpkgs {
        inherit system;
        overlays = [ typelevel-nix.overlays.default ];
      };
    in
    {
      devShell = pkgs.devshell.mkShell {
        imports = [ typelevel-nix.typelevelShell ];
        name = "scala-native-shell";
        devshell.packages = with pkgs; [
          async-profiler
          clang-tools
          linuxPackages.perf
          git
          gnumake
          (python3.withPackages (python-pkgs: [
            python-pkgs.sphinx
            python-pkgs.myst-parser
            python-pkgs.sphinx-markdown-tables
            (python-pkgs.callPackage ./nix/sphinx-last-updated-by-git.nix {})
          ]))
        ];
        typelevelShell = {
          jdk.package = pkgs.jdk17;
          native = {
            enable = true;
            libraries = with pkgs; [
              boehmgc
              libunwind
              zlib
            ];
          };
        };
        env = [
          {
            name = "NIX_CFLAGS_COMPILE";
            value = "-Wno-unused-command-line-argument";
          }
        ];
      };
    }
  );
}
