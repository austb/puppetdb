test_name "test postgresql database restart handling to ensure we recover from a restart" do
  step "clear puppetdb database" do
    clear_and_restart_puppetdb(database)
  end

  with_puppet_running_on master, {
    'master' => {
      'autosign' => 'true'
    }} do

    step "Run agents once to activate nodes" do
      run_agent_on agents, "--test --server #{master}"
    end
  end

  step "Verify that the number of active nodes is what we expect" do
    check_record_count("nodes", agents.length)
  end

  step "Verify that one factset was stored for each node" do
    check_record_count("factsets", agents.length)
  end

  step "Verify that one catalog was stored for each node" do
    check_record_count("catalogs", agents.length)
  end

  step "Verify that one report was stored for each node" do
    check_record_count("reports", agents.length)
  end

  restart_puppetdb(database)

  step "Verify puppetdb can be queried after restarting" do
    check_record_count("nodes", agents.length)
  end

  restart_postgres(database)

  step "Verify puppetdb can be queried after restarting" do
    check_record_count("nodes", agents.length)
  end
end
