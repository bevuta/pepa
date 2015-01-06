users.extraUsers = {
  pepa = {
    createHome = false;
  };
};

networking.firewall.enable = false;

services.postgresql = { 
  enable = true; 
  package = pkgs.postgresql94;
  enableTCPIP = true;
  authentication = ''
    host all all 0.0.0.0/0 trust
  '';
  initialScript = pkgs.writeText "initial.sql" ''
    CREATE USER pepa;
    CREATE DATABASE pepa OWNER pepa;
  '';
};
