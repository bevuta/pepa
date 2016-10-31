let
  pkgs = import <nixpkgs> {};
  stdenv = pkgs.stdenv;
in rec {
  meinestadtEnv = stdenv.mkDerivation rec {
    name = "meinestadt-env";
    version = "1.2.3.4";
    src = ".";
    buildInputs = with pkgs; [ 
      cuneiform
      ghostscript
      jdk8
      leiningen
      pdftk 
      phantomjs
      poppler_utils
      tesseract
    ];
  };
 }
