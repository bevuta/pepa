let
  pkgs = import <nixpkgs> {};
  stdenv = pkgs.stdenv;
in rec {
  meinestadtEnv = stdenv.mkDerivation rec {
    name = "meinestadt-env";
    version = "1.2.3.4";
    src = ".";
    buildInputs = with pkgs; [ 
      leiningen
      jdk8
      cuneiform
      ghostscript
      pdftk 
      poppler_utils
      tesseract
    ];
  };
 }
