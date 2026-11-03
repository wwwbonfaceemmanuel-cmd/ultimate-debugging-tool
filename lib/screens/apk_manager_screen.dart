import 'app_details_screen.dart';
import 'package:flutter/material.dart';

import '../services/application_inventory_service.dart';

class ApkManagerScreen extends StatefulWidget {
  const ApkManagerScreen({super.key});

  @override
  State<ApkManagerScreen> createState() => _ApkManagerScreenState();
}

class _ApkManagerScreenState extends State<ApkManagerScreen> {
  final ApplicationInventoryService _inventoryService =
      ApplicationInventoryService();

  final TextEditingController _searchController =
      TextEditingController();

  List<InstalledApplication> _applications = [];

  bool _loading = true;
  String? _error;
  String _selectedFilter = 'ALL';

  @override
  void initState() {
    super.initState();
    _loadApplications();
  }

  Future<void> _loadApplications() async {
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      final applications =
          await _inventoryService.getInstalledApplications();

      if (!mounted) return;

      setState(() {
        _applications = applications;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;

      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  List<InstalledApplication> get _filteredApplications {
    final query = _searchController.text.trim().toLowerCase();

    return _applications.where((app) {
      final matchesSearch =
          app.appName.toLowerCase().contains(query) ||
          app.packageName.toLowerCase().contains(query);

      final matchesFilter =
          _selectedFilter == 'ALL' ||
          (_selectedFilter == 'USER' && !app.isSystemApp) ||
          (_selectedFilter == 'SYSTEM' && app.isSystemApp);

      return matchesSearch && matchesFilter;
    }).toList();
  }

  Widget _buildSummaryCard() {
    final userApps =
        _applications.where((app) => !app.isSystemApp).length;

    final systemApps =
        _applications.where((app) => app.isSystemApp).length;

    return Card(
      color: const Color(0xFF172638),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'APPLICATION INVENTORY',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                letterSpacing: 1,
                color: Colors.white,
              ),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: _summaryItem(
                    'TOTAL APPS',
                    _applications.length.toString(),
                    Colors.lightBlueAccent,
                  ),
                ),
                Expanded(
                  child: _summaryItem(
                    'USER APPS',
                    userApps.toString(),
                    Colors.greenAccent,
                  ),
                ),
                Expanded(
                  child: _summaryItem(
                    'SYSTEM APPS',
                    systemApps.toString(),
                    Colors.orangeAccent,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _summaryItem(
    String title,
    String value,
    Color color,
  ) {
    return Column(
      children: [
        Text(
          value,
          style: TextStyle(
            color: color,
            fontSize: 24,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          title,
          textAlign: TextAlign.center,
          style: const TextStyle(
            color: Colors.white60,
            fontSize: 9,
            fontWeight: FontWeight.bold,
          ),
        ),
      ],
    );
  }

  Widget _buildFilterButton(String filter) {
    final selected = _selectedFilter == filter;

    return ChoiceChip(
      label: Text(filter),
      selected: selected,
      onSelected: (_) {
        setState(() {
          _selectedFilter = filter;
        });
      },
    );
  }

  Widget _buildApplicationCard(InstalledApplication app) {
    final appColor = app.isSystemApp
        ? Colors.orangeAccent
        : Colors.greenAccent;

    return Card(
      color: const Color(0xFF172638),
      margin: const EdgeInsets.only(bottom: 10),
      child: ListTile(onTap: () {
  Navigator.push(
    context,
    MaterialPageRoute(
      builder: (context) => AppDetailsScreen(app: app),
    ),
  );
},
        contentPadding: const EdgeInsets.all(12),
        leading: CircleAvatar(
          backgroundColor: appColor.withOpacity(0.15),
          child: Icon(
            app.isSystemApp
                ? Icons.settings
                : Icons.android,
            color: appColor,
          ),
        ),
        title: Text(
          app.appName,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            color: Colors.white,
            fontWeight: FontWeight.bold,
          ),
        ),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                app.packageName,
                style: const TextStyle(
                  color: Colors.white60,
                  fontSize: 11,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                'Version: ${app.versionName ?? "Unknown"}',
                style: const TextStyle(
                  color: Colors.white70,
                  fontSize: 11,
                ),
              ),
              const SizedBox(height: 6),
              Wrap(
                spacing: 6,
                children: [
                  _statusLabel(
                    app.isSystemApp ? 'SYSTEM' : 'USER',
                    appColor,
                  ),
                  _statusLabel(
                    app.enabled ? 'ENABLED' : 'DISABLED',
                    app.enabled
                        ? Colors.greenAccent
                        : Colors.redAccent,
                  ),
                  if (app.hasLaunchIntent)
                    _statusLabel(
                      'LAUNCHABLE',
                      Colors.lightBlueAccent,
                    ),
                ],
              ),
            ],
          ),
        ),
        isThreeLine: true,
      ),
    );
  }

  Widget _statusLabel(String label, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(
        horizontal: 7,
        vertical: 4,
      ),
      decoration: BoxDecoration(
        color: color.withOpacity(0.12),
        borderRadius: BorderRadius.circular(5),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 9,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final visibleApplications = _filteredApplications;

    return Scaffold(
      backgroundColor: const Color(0xFF0B1424),
      appBar: AppBar(
        backgroundColor: const Color(0xFF101D32),
        foregroundColor: Colors.white,
        title: const Text('APK & Application Manager'),
        actions: [
          IconButton(
            onPressed: _loading ? null : _loadApplications,
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh applications',
          ),
        ],
      ),
      body: SafeArea(
        child: _loading
            ? const Center(
                child: CircularProgressIndicator(),
              )
            : RefreshIndicator(
                onRefresh: _loadApplications,
                child: ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    _buildSummaryCard(),

                    const SizedBox(height: 20),

                    const Text(
                      'INSTALLED APPLICATIONS',
                      style: TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 1,
                      ),
                    ),

                    const SizedBox(height: 12),

                    TextField(
                      controller: _searchController,
                      onChanged: (_) => setState(() {}),
                      style: const TextStyle(color: Colors.white),
                      decoration: InputDecoration(
                        hintText: 'Search name or package...',
                        hintStyle: const TextStyle(
                          color: Colors.white38,
                        ),
                        prefixIcon: const Icon(Icons.search),
                        filled: true,
                        fillColor: const Color(0xFF172638),
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12),
                          borderSide: BorderSide.none,
                        ),
                      ),
                    ),

                    const SizedBox(height: 12),

                    Wrap(
                      spacing: 8,
                      children: [
                        _buildFilterButton('ALL'),
                        _buildFilterButton('USER'),
                        _buildFilterButton('SYSTEM'),
                      ],
                    ),

                    const SizedBox(height: 12),

                    if (_error != null)
                      Card(
                        color: const Color(0xFF38232A),
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Column(
                            children: [
                              const Icon(
                                Icons.error_outline,
                                color: Colors.redAccent,
                                size: 36,
                              ),
                              const SizedBox(height: 10),
                              const Text(
                                'Unable to retrieve applications',
                                style: TextStyle(
                                  color: Colors.white,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              const SizedBox(height: 8),
                              Text(
                                _error!,
                                style: const TextStyle(
                                  color: Colors.white70,
                                  fontSize: 12,
                                ),
                              ),
                              const SizedBox(height: 12),
                              ElevatedButton.icon(
                                onPressed: _loadApplications,
                                icon: const Icon(Icons.refresh),
                                label: const Text('Try Again'),
                              ),
                            ],
                          ),
                        ),
                      )
                    else if (visibleApplications.isEmpty)
                      const Padding(
                        padding: EdgeInsets.symmetric(vertical: 40),
                        child: Column(
                          children: [
                            Icon(
                              Icons.android,
                              color: Colors.white38,
                              size: 50,
                            ),
                            SizedBox(height: 12),
                            Text(
                              'No applications found.',
                              style: TextStyle(
                                color: Colors.white70,
                              ),
                            ),
                          ],
                        ),
                      )
                    else ...[
                      Text(
                        '${visibleApplications.length} application(s) found',
                        style: const TextStyle(
                          color: Colors.greenAccent,
                          fontSize: 12,
                        ),
                      ),
                      const SizedBox(height: 12),
                      ...visibleApplications.map(
                        _buildApplicationCard,
                      ),
                    ],
                  ],
                ),
              ),
      ),
    );
  }
}
