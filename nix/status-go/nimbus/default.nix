{ stdenv, callPackage, fetchFromGitHub }:

let
  inherit (stdenv.lib) strings;

  rev = "501455b0cd2e74c451bc1743e2f1070a3fee1343";
  owner = "status-im";
  repo = "nimbus";
  sha256 = "0nxh3hh8fib3hlmvs5d67h6cq3kyap94pa9w7ixsfa5285ila17h";
  src = fetchFromGitHub {
    inherit rev owner repo sha256;
    name = "${repo}-${strings.substring 0 7 rev}-source";
    fetchSubmodules = true;
  };
  nimbusDeriv = import "${src}/nix/default.nix";
  #nimbusDeriv = import "/home/pedro/src/github.com/status-im/nimbus/nix/default.nix";

in nimbusDeriv
